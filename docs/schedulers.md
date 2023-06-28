---
id: schedulers
title: Schedulers
---

As Cats Effect is a runtime system, it ultimately must deal with the problem of how best to *execute* the programs which are defined using its concrete implementation (`IO`). Fibers are an incredibly powerful model, but they don't map 1:1 or even 1:n with any JVM or JavaScript construct, which means that some interpretation is required. The fashion in which this is achieved has a profound impact on the performance and elasticity of programs written using `IO`.

This is true across both the JVM and JavaScript, and while it seems intuitive that JavaScript scheduling would be a simpler problem (due to its single-threaded nature), there are still some significant subtleties which become relevant in real-world applications.

## JVM

`IO` programs and fibers are ultimately executed on JVM threads, which are themselves mapped directly to kernel threads and, ultimately (when scheduled), to processors. Determining the optimal method of mapping a real-world, concurrent application down to kernel-level threads is an extremely complex problem, and the details of *why* Cats Effect has chosen the particular strategies it employs are discussed later in this section.

As an executive overview though, `IO` leverages these mostly independent thread pools to evaluate programs:

- **A work-stealing pool for *computation***, consisting of exactly the same number of `Thread`s as there are hardware processors (minimum: 2)
- **An unbounded blocking pool**, defaulting to zero `Thread`s and allocating as-needed (with caching and downsizing) to meet demand for *blocking* operations (technically this separate thread pool is built into the work-stealing pool, and handled transparently)

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

Another excellent example is file I/O (such as `FileInputStream`). Filesystems are also a good example of exceptionally old software designed for a previous age being brought into the modern world, and to that end, they are almost universally blocking. Windows local NTFS mounts are a notable exception to this paradigm, but all macOS filesystem operations are blocking at the OS level, as are (in practice) nearly all Linux filesystem operations.

File I/O is effectively unavoidable in any application, but it too means blocking a thread while the kernel fishes out the bytes your application has requested.

Clearly, we cannot simply pretend this problem does not exist. This is why the `blocking` and `interruptible`/`interruptibleMany` functions on `IO` are so important: they declare to the `IO` runtime that the effect in question will block a thread, and it *must* be shifted to a blocking thread:

```scala
IO.blocking(url1 == url2) // => IO[Boolean]
```

In the above, the `URL#equals` effect (along with its associated blocking DNS resolution) is moved off of the precious thread-stealing compute pool and onto a blocking thread. This worker thread then blocks (which causes the kernel to remove it from the processor) for as long as necessary to complete the DNS resolution, after which it returns and completes the boolean comparison. After this effect completes, `IO` shifts the fiber back to the work-stealing compute pool, ensuring all of the benefits are applied and the blocking worker thread can be reused.

This scheduling dance is handled for you entirely automatically, and the only extra work which must be performed by you, the user, is explicitly declaring your thread-blocking effects as `blocking` or `interruptible`/`interruptibleMany` (the `interruptible`/`interruptibleMany` implementation is similar to `blocking` except that it also utilizes thread interruption to allow fiber cancelation to function even within thread-blocking effects).

## JavaScript

While JavaScript runtimes are single-threaded, and thus do not have the *m:n* problem that gives rise to the complexity of work-stealing pools and such on the JVM, the extreme heterogeneity of JavaScript runtime environments presents its own series of problems. Additionally, fibers are literally a form of multi-threading, and emulating multi-threading on top of a single-threaded runtime is a surprisingly complicated task.

Ultimately, though, `IO` is able to achieve this with exceptionally high performance, and without interfering with other mechanisms which leverage the event loop (such as animations in the browser or I/O on the server). The techniques which are used by `IO` to achieve this have been extracted into the [scala-js-macrotask-executor](https://github.com/scala-js/scala-js-macrotask-executor) project that runs across all platforms which support ScalaJS. Optimal performance is available in the following environments:

- [NodeJS 0.9.1+](https://nodejs.org/api/timers.html#timers_setimmediate_callback_args)
- [Browsers implementing `window.postMessage()`](https://developer.mozilla.org/en-US/docs/Web/API/Window/postMessage#browser_compatibility), including:
  - Chrome 1+
  - Safari 4+
  - Internet Explorer 9+ (including Edge)
  - Firefox 3+
  - Opera 9.5+
- [Web Workers implementing `MessageChannel`](https://developer.mozilla.org/en-US/docs/Web/API/MessageChannel#browser_compatibility)

### Yielding

The primary mechanism which needs to be provided for `IO` to successfully implement fibers is often referred to as a *yield*. In JavaScript terms, this means the ability to submit a callback to the event loop which will be invoked at some point in the future when the event loop has capacity. In a sense, it's submitting a callback which "goes to the back of the queue", waiting for everything else to have its turn before running again.

This mechanism, by the way, corresponds *precisely* to the `IO.cede` effect: it cooperatively releases control to the other fibers, allowing them a chance to run before resuming the current fiber.

With this primitive, fibers can be implemented by simply scheduling each one at a time. Each fiber runs until it reaches a *yield point* (usually an asynchronous call or a `cede` effect), at which point it yields back to the event loop and allows the other fibers to run. Eventually, those fibers will yield back around the queue until the first fiber has a chance to run again, and the process continues. Nothing is actually running *simultaneously*, but parallelism is still expressed as a concept in a way which is semantically compatible with the JVM, which allows libraries and application code to fully cross-compile despite the differences in platform semantics.

The *problem* is how to implement this yield operation in a fashion which is highly performant, compatible across all JavaScript environments, and doesn't interfere with other JavaScript functionality. Perhaps unsurprisingly, this is quite hard. Find the full story in the [scala-js-macrotask-executor](https://github.com/scala-js/scala-js-macrotask-executor) project's README.

For the most part, you should never have to worry about any of this. Just understand that Cats Effect `IO` schedules fibers to play nicely with other asynchronous events such as I/O or DOM changes while avoiding overhead.

## Scala Native

The [Scala Native](https://github.com/scala-native/scala-native) runtime is [single-threaded](https://scala-native.org/en/latest/user/lang.html#multithreading), similarly to ScalaJS. That's why the `IO#unsafeRunSync` is not available.
Be careful with `IO.blocking(...)` as it blocks the thread since there is no dedicated blocking thread pool.
For more in-depth details, see the [article](https://typelevel.org/blog/2022/09/19/typelevel-native.html#how-does-it-work) with explanations of how the Native runtime works.
