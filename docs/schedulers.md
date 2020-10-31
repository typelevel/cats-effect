# Schedulers

As Cats Effect is a runtime system, it ultimately must deal with the problem of how best to *execute* the programs which are defined using its concrete implementation (`IO`). Fibers are an incredibly powerful model, but they don't map 1:1 or even 1:n with any JVM or JavaScript construct, which means that some interpretation is required. The fashion in which this is achieved has a profound impact on the performance and elasticity of programs written using `IO`.

This is true across both the JVM and JavaScript, and while it seems intuitive that JavaScript scheduling would be a simpler problem (due to its single-threaded nature), there are still some significant subtleties which become relevant in real-world applications.

## JVM

`IO` programs and fibers are ultimately executed on JVM threads, which are themselves mapped directly to kernel threads and, ultimately (when scheduled), to processors. Determining the optimal method of mapping a real-world, concurrent application down to kernel-level threads is an extremely complex problem, and the details of *why* Cats Effect has chosen the particular strategies it employs are discussed later in this section.

As an executive overview though, `IO` leverages three independent thread pools to evaluate programs:

- **A work-stealing pool for *computation***, consisting of exactly the same number of `Thread`s as there are hardware processors (minimum: 2)
- **A single-threaded schedule dispatcher**, consisting of a single maximum-priority `Thread` which dispatches `sleep`s with high precision
- **An unbounded blocking pool**, defaulting to zero `Thread`s and allocating as-needed (with caching and downsizing) to meet demand for *blocking* operations

The *vast* majority of any program will execute on the work-stealing pool. Shifting computation between these pools is done transparently to the user, and for the most part, you don't even need to know they are there.

The work-stealing pool is an extremely high-performance implementation derived from the [Tokio](https://github.com/tokio-rs/tokio) Rust framework. It is fiber-aware, meaning that it understands the nature of asynchronous continuations within a given fiber, as well as common data patterns that arise from constructs such as `start`. This also allows the worker threads to cut their management overhead to near-zero, since fibers *themselves* represent the pending work queue. This also ensures that, as much as possible, any given fiber will remain pinned to a single thread (though it may share time with other fibers), which allows the OS kernel to optimize processor cache utilization and avoid expensive rescheduling whenever possible.

Work-stealing is a particularly useful scheduling algorithm for modern, heavily-parallelized applications due to its removal of the single-point-of-contention problem. In a naive thread pool implementation, every task is placed onto a single shared queue, and all of the worker threads poll that queue to take the next task (if available). This single queue implementation works reasonably well for a small number of worker threads (think: one to ten), but very quickly becomes a significant overhead when the number of workers increases. For most applications, the optimal number of workers is exactly equal to the number of hardware processors, which means that a heavy-duty, compute-optimized server instance with hundreds of cores will almost immediately bog down in coordination overhead as the threads compete for access to the single shared resource.

In a work-stealing implementation, worker threads spend most of their time solely focused on their own work queue, never coordinating with the other threads in any way whatsoever. This also has the side-benefit of optimizing processor cache utilization even further and avoiding expensive memory barriers. When a worker thread empties out its queue, it goes looking for another thread to "steal" from, which entails taking half of *that* worker's tasks and moving them to its own local queue. This is a small point of contention, but only between those two threads (while the rest of the pool remains undisturbed).

This means that a work-stealing pool can scale vertically to almost any number of worker threads without any increased overhead. It also means that the pool becomes *more* efficient as load increases. When every worker is fully saturated, with their own queues full of fibers waiting to be executed, the amount of time spent stealing from other threads drops to zero (since all workers have local tasks), which eliminates all memory barriers and contention from the scheduler exactly when your application needs it most: when it is at maximum load.

**The bottom line:** `IO`'s work-stealing scheduler results in a **1.5x to 5x** performance increase (relative to a fixed-size `ThreadPoolExecutor`) under high load when tested on consumer hardware with synthetic benchmarks. Extremely compute-optimized instances with hundreds of cores should see even more dramatic improvements. Additionally, the synthetic benchmark fails to take advantage of a number of the most impactful optimizations within the scheduler (such as thread affinity), meaning that realistic workloads should see much better results.

### Handling Blocking

Thread blocking is a major problem in nearly all asynchronous applications, and really, all applications *on the JVM*. It is particularly an issue for Cats Effect applications, given that the default application pool uses a fixed number of threads (as described above). A single worker thread blocked on `Thread.sleep` (or, more realistically, a JDBC call), would have a radical impact on the throughput of the entire application. If enough threads block on things, eventually the application could entirely starve itself of threads and come to a complete halt.

Solving this problem while balancing the needs of the JVM's garbage collector and the operating system's own scheduling algorithm is a complicated problem, and one which is usually best handled by having a separate thread pool *specifically* and solely for blocking operations.

Blocking operations are, in practice, unavoidable. Some of this has to do with the JVM's design, which predates almost everything recognizable about the modern computing space. As an example of this, the `URL` class performs DNS resolution when constructed (yes, including network calls). DNS clients are particularly challenging to write due to the age and complexity of the specification, as well as the proliferation of edge cases which must be handled across varied network infrastructure, which means that the only reliable implementations *tend* to be baked into the operating system and provided to applications as a library call.

The JVM uses just such a native library call *within the `URL#equals` comparison*, and that library call blocks until the DNS resolution has been completed, because the underlying DNS client within every major OS is *itself* blocking and uses blocking network I/O. So this means that every time you innocently call `==` on `URL`s (or if you're unlucky enough to use them as `Map` keys), you're blocking a thread for as long as it takes for your DNS server to respond with an answer (or non-answer).

Another excellent example is file I/O (such as `FileInputStream`). Filesystems are also a good example of exceptionally old software designed for a previous age being brought into the modern world, and to that end, they are almost universally blocking. Windows local NTFS mounts are a notable exception to this paradigm, but all macOS filesystem operations are blocking at the OS level, as are (in practice) nearly all Linux filesystem operations (io_uring is changing this, but it's still exceptionally new and not yet universally available, seeing its first public release in May 2019). 

File I/O is effectively unavoidable in any application, but it too means blocking a thread while the kernel fishes out the bytes your application has requested.

Clearly, we cannot simply pretend this problem does not exist. This is why the `blocking` and `interruptible` functions on `IO` are so important: they declare to the `IO` runtime that the effect in question will block a thread, and it *must* be shifted to the blocking worker pool:

```scala
IO.blocking(url1 == url2) // => IO[Boolean]
```

In the above, `URL#equals` effect (along with its associated blocking DNS resolution) is moved off of the precious thread-stealing compute pool and onto an unbounded blocking pool. This worker thread then blocks (which causes the kernel to remove it from the processor) for as long as necessary to complete the DNS resolution, after which it returns and completes the boolean comparison. As soon as this effect completes, `IO` *immediately* shifts the fiber back to the work-stealing compute pool, ensuring all of the benefits are applied and the blocking worker thread can be returned to its pool.

This scheduling dance is handled for you entirely automatically, and the only extra work which must be performed by you, the user, is explicitly declaring your thread-blocking effects as `blocking` or `interruptible` (the `interruptible` implementation is similar to `blocking` except that it also utilizes thread interruption to allow fiber cancellation to function even within thread-blocking effects).

## JavaScript

While JavaScript runtimes are single-threaded, and thus do not have the *m:n* problem that gives rise to the complexity of work-stealing pools and such on the JVM, the extreme heterogeneity of JavaScript runtime environments presents its own series of problems. Additionally, fibers are literally a form of multi-threading, and emulating multi-threading on top of a single-threaded runtime is a surprisingly complicated task.

Ultimately, though, `IO` is able to achieve this with exceptionally high performance, and without interfering with other mechanisms which leverage the event loop (such as animations in the browser or I/O on the server). The techniques which are used by `IO` to achieve this run across all platforms which support ScalaJS. However, optimal performance is available only in the following environments:

- NodeJS (all versions)
- Internet Explorer 9+ (including Edge)
- Firefox 3+
- Opera 9.5+
- *All WebKit browsers*

Notably, at the present time, web workers are *not* supported at maximum performance. Cats Effect `IO` does run under web workers, but with degraded performance on operations involving fibers. There's a known fix for this bug, and the only blocker is really just setting up CI infrastructure which can run the `IO` test suite within a web worker.

**Also note:** At present, a bug in Dotty prevents the use of the high performance scheduler in ScalaJS applications compiled with Scala 0.27.0-RC1. This is expected to be fixed in Scala 3.0.0-M1.

### Yielding

The primary mechanism which needs to be provided for `IO` to successfully implement fibers is often referred to as a *yield*. In JavaScript terms, this means the ability to submit a callback to the event loop which will be invoked at some point in the future when the event loop has capacity. In a sense, it's submitting a callback which "goes to the back of the queue", waiting for everything else to have its turn before running again.

This mechanism, by the way, corresponds *precisely* to the `IO.cede` effect: it cooperatively releases control to the other fibers, allowing them a chance to run before resuming the current fiber.

With this primitive, fibers can be implemented by simply scheduling each one at a time. Each fiber runs until it reaches a *yield point* (usually an asynchronous call or a `cede` effect), at which point it yields back to the event loop and allows the other fibers to run. Eventually, those fibers will yield back around the queue until the first fiber has a chance to run again, and the process continues. Nothing is actually running *simultaneously*, but parallelism is still expressed as a concept in a way which is semantically compatible with the JVM, which allows libraries and application code to fully cross-compile despite the the differences in platform semantics.

The *problem* is how to implement this yield operation in a fashion which is highly performant, compatible across all JavaScript environments, and doesn't interfere with other JavaScript functionality. Perhaps unsurprisingly, this is quite hard.

### `setTimeout`

The most obvious mechanism for achieving this yield semantic is the `setTimeout` function. Available in all browsers since the dawn of time, `setTimeout` takes two arguments: a time delay and a callback to invoke. The callback is invoked by the event loop once the time delay expires, and this is implemented by pushing the callback onto the back of the event queue at the appropriate time. In fact, this is precisely how `IO.sleep` is implemented. Calling `setTimeout` with a delay of `0` would seem to achieve *exactly* the semantics we want: yield back to the event loop and allow it to resume our callback when it's our turn once again.

Unfortunately, `setTimeout` is slow. Very, very, very slow. The timing mechanism imposes quite a bit of overhead, even when the delay is `0`, and there are other complexities which ultimately impose a performance penalty too severe to accept. Any significant application of fibers, backed by `setTimeout`, would be almost unusable.

To make matters worse, *browsers* clamp the resolution of `setTimeout` to four milliseconds once the number of nested timer invocations exceeds four. By nested timer invocations, we mean something like this:

```javascript
setTimeout(() => {
  setTimeout(() => {
    setTimeout(() => {
      setTimeout(() => {
        setTimeout(() => {
          // this one (and all after it) are clamped!
        }, 0);
      }, 0);
    }, 0);
  }, 0);
}, 0);
```

Each timeout sets a new timeout, and so on and so on. This is exactly the sort of situation that fibers are in, where each yield resumes a fiber which, in turn will yield... and when resumed, will eventually yield again, etc etc. This is exactly where we see clamping. In particular, the innermost `setTimeout` in this example will be clamped to 4 milliseconds (meaning there is no difference between `setTimeout(.., 0)` and `setTimeout(.., 4)`), which would slow down fiber evaluation *even more*.

### `Promise`

Clearly another solution is required. Fortunately, recent browser and NodeJS versions have just such a solution: `Promise`.

`Promise` has a lot of problems associated with its API design (its `then` method functions as a dynamically typed combination of `map` and `flatMap`), but the *concept* behind it is very similar to the `Future` monad: it represents a running asynchronous computation which you can chain together with other such computations. JavaScript even now has a special syntax for working with promises (since it lacks anything like `for`-comprehensions), in the form of `async` and `await`.

Even better still, `Promise` is *already* wrapped by ScalaJS itself in a thread pool-like API. Specifically, if you evaluate `ExecutionContext.global` on a platform which supports `Promise`, the resulting executor will use `Promise.then` under the surface to run your action when you call the `execute` method. (in case you were wondering, the `global` executor on non-`Promise` platforms is backed by `setTimeout` as a fallback)

Unfortunately, this doesn't work well for a very "JavaScript" set of reasons. The following example demonstrates the problem fairly clearly:

```scala
IO.cede.foreverM.start flatMap { fiber =>
  IO.sleep(5.seconds) >> fiber.cancel
}
```

This program does not terminate if fibers are implemented in terms of `Promise` (which, again, is the default `ExecutionContext` on ScalaJS).

Intuitively, it seems like this program should behave just fine on JavaScript. We have a single fiber which is repeatedly yielding (using `cede`) and doing nothing else, which means that the other fiber should have more than enough chances to execute even on a single-threaded runtime. The other fiber sleeps for five seconds, and then cancels the first fiber. However, if you instrument what's going on here, when `cede` is backed by `Promise`, the `sleep` never has a chance to evaluate!

The problem is the fact that JavaScript has *two* event queues: the *macrotask* queue and the *microtask* queue. The macrotask queue is used for `setTimeout`, network I/O (in NodeJS), DOM events, and so on. The microtask queue is used for `Promise`. (also, confusingly, there are some things which use the macrotask queue but have microtask semantics; we'll come back to this) The problem is that, when the microtask queue has work to perform, it starts an event loop which is enqueued *on the macrotask queue* and fully runs until the microtask queue is empty, preventing the *next* macrotask queue entry from ever evaluating! So in our example, the microtask queue is constantly full because our `IO.cede.foreverM` fiber is just looping around and yielding over and over and over, meaning that the macrotask `IO.sleep` never has a chance to run.

This is horrible, even by JavaScript standards. And it's also unusable for fibers, since we need `IO.sleep` to work (not to mention all other events).

### `setImmediate`

Fortunately, we aren't the only ones to have this problem. What we *want* is something which uses the macrotask queue (so we play nicely with `IO.sleep` and other async events), but which doesn't have as much overhead as `setTimeout`. The answer is `setImmediate`.

The `setImmediate` function was first introduced in NodeJS, and its purpose is to solve *exactly* this problem: a faster `setTimeout(..., 0)`. It doesn't include a delay mechanism of any sort, it simply takes a callback and immediately submits it to the event loop, which in turn will run the callback as soon as its turn comes up. These are precisely the semantics needed to implement fibers.

Unfortunately, `setImmediate` isn't available on every platform. For reasons of... their own, Mozilla, Google, and Apple have all strenuously objected to the inclusion of `setImmediate` in the W3C standard set, despite the proposal (which originated at Microsoft) and obvious usefulness. This in turn has resulted in an all-too familiar patchwork of inconsistency across the JavaScript space.

That's the bad news. The good news is that all modern browsers include *some* sort of functionality which can be exploited to emulate `setImmediate` with similar performance characteristics. If you're interested in the nitty-gritty details of how this works, you are referred to [this excellent readme](https://github.com/YuzuJS/setImmediate#the-tricks).

Cats Effect implements *most* of the `setImmediate` polyfill in terms of ScalaJS, wrapped up in an `ExecutionContext` interface to be compatible with user-land code. If you evaluate `IO.executionContext` on ScalaJS, this is the executor you will receive.

The only elements of the polyfill which are *not* implemented by Cats Effect are as follows:

- `process.nextTick` is used by the JavaScript polyfill when running on NodeJS versions below 0.9. However, ScalaJS itself does not support NodeJS 0.9 or below, so there's really no point in supporting this case.
- Similarly, older versions of IE (6 through 8, specifically) allow a particular exploitation of the `onreadystatechange` event fired when a `<script>` element is inserted into the DOM. However, ScalaJS does not support these environments *either*, and so there is no benefit to implementing this case.
- Web workers have entirely alien semantics for task queuing and no access to the DOM, but they do have `MessageChannel` which accomplishes exactly what we need. However, they're very hard to test in CI (apparently), and so Cats Effect does not yet implement this leg of the polyfill. There are plans to fix this.

On environments where the polyfill is unsupported, `setTimeout` is still used as a final fallback.

For the most part, you should never have to worry about any of this. Just understand that Cats Effect `IO` schedules fibers with macrotask semantics while avoiding overhead and clamping, ensuring that it plays nicely with other asynchronous events such as I/O or DOM changes.
