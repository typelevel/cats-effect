---
id: starvation-and-tuning
title: Starvation and Tuning
---

All Cats Effect applications constructed via `IOApp` have an automatic mechanism which periodically checks to see if the application runtime is starving for compute resources. If you ever see warnings which look like the following, they are the result of this mechanism automatically detecting that the responsiveness of your application runtime is below the configured threshold. Note that the timestamp is the time when the starvation was detected, which is not precisely the time when starvation (or the task that is responsible) began.

```
2023-01-28T00:16:24.101Z [WARNING] Your app's responsiveness to a new asynchronous
event (such as a new connection, an upstream response, or a timer) was in excess
of 40 milliseconds. Your CPU is probably starving. Consider increasing the
granularity of your delays or adding more cedes. This may also be a sign that you
are unintentionally running blocking I/O operations (such as File or InetAddress)
without the blocking combinator.
```

While the implementation of this warning is very simple and very reliable (it is almost never a false negative), the root cause is often quite complex and difficult to track down. Unfortunately, there is no single action which can fix this issue in all cases. This page is meant to be a discussion of the scenarios which can give rise to starvation, as well as a list of commonly-useful diagnostic steps and root causes.

## Starvation

An important first step is to understand the definition of *starvation* in this context. "Starvation" can refer to any scenario in which a task is unable to obtain access to a resource it needs to continue. In a more aggregate sense, the warning given above is actually something of a non-sequitur: a *CPU* cannot starve since a CPU is a resource and not a task, but tasks may starve when they are unable to gain access to the CPU.

In most cases, starvation in the strictest definition (complete inability to access a resource) is relatively rare. While it is certainly possible to engineer such scenarios in Cats Effect, doing so is usually intuitively bad:

```scala mdoc:silent
import cats.effect._
import cats.syntax.all._

object StarveThyself extends IOApp.Simple {
  val run =
    0.until(100).toList parTraverse_ { i =>
      IO {
        println(s"running #$i")
        Thread.sleep(10000)
      }
    }
}
```

The above program will very quickly starve the Cats Effect compute pool, effectively denying fibers access to the CPU. You can observe this behavior by the fact that only the first *n* `println`s will run, where *n* is the number of threads in the compute pool (by default, equal to the number of physical threads in the underlying hardware). In fact, this program will *so* effectively starve all tasks of CPU that even the starvation checker itself will be unable to run and thus fail to detect this scenario!

Fortunately (or perhaps, unfortunately), most starvation scenarios are a lot more subtle than this.

It's also possible for tasks to starve for access to resources which are not compute-related. For example, if a task starves for access to the JVM heap, it will throw an `OutOfMemoryError`. If it starves for access to additional stack frames, it will throw a `StackOverflowError`.

To make matters substantially more complicated, not all starvation is caused by behavior of your own application. If your JVM has been configured with an exceptionally small heap size, your application may throw an `OutOfMemoryError` without a memory leak or any other erroneous behavior. Similarly, if another process in the same operating system has a file handle leak, your process may crash when it attempts to handle a new network connection or log to disk. In each of these cases, the problem is *external* to your application, but you can still detect the impacts (in some cases, fatally).

Similarly, CPU starvation can be caused by issues in your own application – such as hard-blocking, or compute-bound tasks that hog the thread – but it can also be caused by issues outside your direct control, such as amount of contending processes on the same system, or even the number of physical threads on the underlying hardware. The only factor accurately measured by the starvation checker is that your application *is starving for access to the CPU*; it cannot determine the root cause of that starvation.

## Understanding the Checker

Before diving into how we can correct issues with CPU starvation, it is worth understanding the mechanism by which Cats Effect is measuring this effect. The code in question is surprisingly simple:

```scala mdoc:silent
import scala.concurrent.duration._

val starvationThreshold = 0.1.seconds
val sleepInterval       = 1.second

val tick: IO[Unit] =
  // grab a time offset from when we started
  IO.monotonic flatMap { start =>
    // sleep for some long interval, then measure when we woke up
    // get the difference (this will always be a bit more than the interval we slept for)
    IO.sleep(sleepInterval) *> IO.monotonic.map(_ - start) flatMap { delta =>
      // the delta here is the sum of the sleep interval and the amount of *lag* in the scheduler
      // specifically, the lag is the time between requesting CPU access and actually getting it
      IO.println("starvation detected").whenA(delta - sleepInterval > starvationThreshold)
    }
  }
// add an initial delay
// run the check infinitely, forked to a separate fiber
val checker: IO[Unit] = (IO.sleep(1.second) *> tick.foreverM).start.void
```

There are some flaws in this type of test, most notably that it is measuring not only the CPU but also the overhead associated with `IO.sleep`, but for the most part it tends to do a very good job of isolating exactly and only the action of enqueuing a new asynchronous event into the Cats Effect runtime and then reacting to that action on the CPU.

**Critically, this type of formulation approximates the way that any other asynchronous event would behave.** For example, if a new connection came in on a server socket, the time between when that I/O event is registered by the OS kernel and when the `IO.async` wrapping around the server socket is fired and its fiber active on a carrier thread will converge to `delta` time. Concretely, this means that if `delta` is more than `100.millis` (the default threshold for producing the warning), then the response latency on acknowledging a new network request within that same application runtime will *also* be more than `100.millis`.

> The starvation checker is a runtime assertion that any latency higher than the threshold (by default, 100 milliseconds) is bad and deserves a warning. If you raise the threshold or, even more dramatically, disable the checker entirely, you are saying that more than 100 milliseconds of latency is acceptable for your application.

To be clear, many applications are entirely fine with latency that exceeds 100 milliseconds! Similarly, many applications are unconcerned with fairness (particularly data-heavy throughput optimized applications), and thus CPU starvation is entirely irrelevant. Consider your SLAs and expected performance characteristics carefully.

## Common Causes

This section contains a very much *not* comprehensive list of common root causes which may be the source of the starvation warnings.

### Blocking Tasks

The easiest and most obvious source of CPU starvation is when blocking tasks are being run on the compute thread pool. One example of this can be seen above, but usually it's a lot less straightforwardly obvious than that one, and tracking down what is or is not blocking in third party libraries (particularly those written for Java) can be challenging. Once the offending code is found, it is relatively easy to swap an `IO(...)` for `IO.blocking(...)` or `IO.interruptible(...)`.

If your application is an `IOApp` then the easiest way to find these offending expressions is to enable thread blocking detection like this:

```scala
object Example extends IOApp.Simple {

  override protected def blockedThreadDetectionEnabled = true

  val run: IO[Unit] = ???
}
```

This instructs the Cats Effect runtime threads to periodically sample the state of a randomly chosen sibling thread. If that thread is found to be in a blocked state then a stack trace will be printed to stderr, which should allow you to directly identify the code that should be wrapped in `IO.blocking` or `IO.interruptible`. Whilst not guaranteed to find problems, this random sampling should find recurrent causes of thread blocking with a high degree of probability. It is however relatively expensive so it is disabled by default and is probably not something that you want to run permanently in a production environment.

If you are not using `IOApp` then the easiest place to start is to simply take a bunch of thread dumps at random. <kbd>Ctrl</kbd>-<kbd>\\</kbd> (or `kill -3 <pid>`) will dump a full list of threads and their current stack traces to standard error. Do a few of these at random intervals and check all of the threads named `io-compute-`. If *any* of them are ever in the `BLOCKED` state, take a look at the stack trace: you have found a rogue blocking task. (note that the stack trace will not be enriched, so you don't get the benefits of fiber tracing in this context, but you should still be able to see the line number where you wrapped the blocking call in `IO`, and this is what needs to be adjusted)

Swapping in an `IO.blocking` is the easiest solution here and will generally resolve the issue. Though, you do still need to be careful, since too much `IO.blocking` can create thread allocation pressure, which may result in CPU starvation of a different variety (see below).

> All file I/O on macOS and Linux (and *most* file I/O on Windows) is fundamentally blocking and will always result in locking up a kernel thread. Similarly, DNS resolution (often via `URL` or `InetAddress`) is also blocking. When looking for rogue blocking side-effects, it is usually best to start with code which you suspect *might* be interacting with the filesystem or the DNS resolver. This is why the warning mentions these two very common cases by name.

### Compute-Bound Work

While hard-blocking threads is a very easy and obvious way to vacuum up all of the threads in the compute pool, it is far from the only one. The following program will similarly starve your application of compute resources:

```scala mdoc:silent
import cats.effect._
import cats.syntax.all._

object StarveThyselfAgain extends IOApp.Simple {
  val run =
    0.until(100).toList parTraverse_ { i =>
      IO.println(s"running #$i") >> IO(while (true) {})
    }
}
```

This is very similar to the example from earlier, where all of the compute threads will be locked up in `Thread.sleep`. In this case, though, all of the compute threads will be busy looping forever in `while (true)`.

Now, most realistic application do not have these types of obvious pitfalls. Instead, it might be something like a series of cryptographic operations (especially asymmetric cryptography, which is much more CPU-intensive than symmetric algorithms), or something innocently tucked away inside of a `map` function. Unlike most forms of thread blocking (which usually arise from blocking I/O), compute-bound actions are often *not* side-effecting!

Regardless of all this, the easiest place to start is still taking a lot of thread dumps. Alternatively, you can attach an async profiler to the application for a more sophisticated version of the "take a lot of thread dumps" strategy. Either way, your goal is to figure out where you're spending all of your time.

Unfortunately, in this scenario, the solution is much less straightforward than simply wrapping code in `IO.blocking`. The problem can be summed up by the following dichotomy:

- When a thread is `BLOCKED`, it will make progress given *time*
- When a thread is `RUNNABLE`, it can only make progress given *physical CPU*

This is an important distinction. It boils down to the fact that, no matter what we do in the application layer, the amount of *physical* resources to which we have access is finite and (essentially) fixed. If all of your threads are busy doing computational work, you cannot simply magic new CPUs into existence in order to free up space for the rest of your application, meaning that allocating new threads (which is what `IO.blocking` ultimately achieves) is not actually going to solve the problem, it merely relocates the issue to the kernel (where it often causes even more problems).

The solution to maintaining fairness in heavily compute-bound applications is usually the following three step process:

1. Try to increase the granularity of your computation by splitting it into multiple `IO` steps
2. Add `IO.cede`s around your computationally-intensive functions
3. Restrict the amount of outstanding compute-heavy work to some fraction of the total number of physical threads (half is a good starting point)

#### Increase Granularity

Increasing the granularity of a computation is usually fairly easy to do. For example:

```scala
data map { d =>
  val x = computeX(d)
  val y = computeY(d)
  x + y
}
```

If `computeX` and `computeY` in the above are both long-running (but not blocking!) computations, then your program will definitely benefit by splitting this single `map` into two:

```scala
data flatMap { d =>
  IO.pure(computeX(d)) map { x =>
    x + computeY(d)
  }
}
```

This is a bit less elegant, and it involves a rather artificial detour by calling `map` on the results of `IO.pure`, but it will still get the job done. What you're doing here is you're giving the `IO` run-loop a bit more space to comprehend and even-out the scheduling of your fibers.

Be careful with this change though, since inserting extra `IO`s results in additional allocations, additional indirection, and thus lower throughput! As a general rule, you would ideally want every non-`async` `IO` to take *exactly* the same amount of time to execute. If you start making that time slice too small, then the allocation and indirection costs of `IO` itself begin to dominate and your application will get slower. If you make that time slice too large, then the Cats Effect runtime will be unable to maintain fair scheduling across your fibers and the result will be CPU starvation.

#### Add `cede`s

As noted above, one of the rough assumptions of the Cats Effect runtime (and indeed, all userspace schedulers) is the fungibility of tasks. That is to say, all tasks require roughly the same amount of resources to execute. From the perspective of the Cats Effect runtime, a "task" is a contiguous block of *n* `IO` stages (e.g. `IO.pure` is a stage, as is `flatMap`, `delay`, etc), where *n* is configured in the runtime (default: 1024). Once every 1024 stages, Cats Effect forces a task boundary (often called an "auto-yield") if a boundary has not been encountered naturally. `IO.async` is a natural boundary, as is `sleep`, `blocking`, `interruptible`, `join` (when the fiber is not already complete), and `cede`.

This is all very important because it relates to how Cats Effect approximates the size of a task. Since tasks are usually 1024 stages in length, and tasks are *assumed* to be roughly the same "size" (in terms of resource footprint) as each other, then it follows that we're also assuming each of the *stages* is roughly the same size as any other stage. Of course, this assumption has significant error bars on it (`IO.pure` is always going to be *much* lighter-weight than `IO(...)`), but we're reasoning about large aggregates here.

The problem with compute-bound work, whether wrapped in `IO(...)` or simply in a `map` or `flatMap` body, is it means that one `IO` stage will require substantially more CPU resources to complete than the other stages. This in turn throws off the balance of stages (and therefore, tasks) within the runtime, which gives rise to fairness and starvation issues. Increasing the granularity of a stage (by splitting one big stage into two smaller ones) as described above can help a lot, but another important mechanism can be simply shrinking the size of a task.

As noted, tasks *default* to 1024 stages in size, but if one of the stages is just as expensive as the other 1023 put together, the appropriate response is to force the Cats Effect runtime to treat the expensive stage as its own task, pushing the other 1023 stages into other tasks. This can be accomplished by sequencing `IO.cede`:

```scala
val expensiveThing: IO[A] = IO(expensiveComputationHere())

// yield before *and* after running expensiveThing
val fairThing = IO.cede *> expensiveThing.guarantee(IO.cede)
```

If `expensiveComputationHere()` is not something that can be split into multiple pieces and is *still* as (or more) expensive than 1024 "normal" `IO` stages (e.g. `flatMap` on most systems requires about 10 nanoseconds to complete, meaning that any task which takes more than 10 *microseconds* should be considered "expensive").

> If you are having a hard time profiling the execution time of individual `IO` stages, the `timed` function might help.

A quick-and-dirty experimental way that this can be established for your specific runtime environment is to run `timed` on `IO.unit` and compare that value to what is produced by `timed` on a more expensive action. For example:

```scala
val expensiveThing: IO[A] = ???

IO.unit.timed flatMap {
  case (baseline, _) =>
    IO.println(s"baseline stage cost is $baseline") >> expensiveThing.timed flatMap {
      case (cost, result) =>
        if (cost / baseline > 1024)
          IO.println("expensiveThing is very expensive").as(result)
        else
          IO.println("the price is right").as(result)
    }
}
```

In the above experiment, we're measuring the cost of a normal `IO` stage on your system and comparing that to the measured cost of a hypothetically-expensive stage. If the expensive stage is more than 1024x the cost of the normal stage, do the `cede` thing shown above.

#### Restrict Work

At the end of the day, no matter what the granularity of your tasks, compute-bound work requires CPU time to complete. There is no magic bullet which can solve this problem simply because it is a *physical* limitation. If you are unable to break an `IO` up into multiple, more granular stages, and even wrapping those stages in `cede`s is insufficient (e.g. if even the individual stages are many orders of magnitude larger than normal tasks), then the only solution which preserves fairness and avoids starvation is throttling.

There is a fundamental tension in compute scheduling between fairness and throughput. Maximum throughput would suggest that we never interrupt tasks and we allow them to be whatever size they need to be, using the CPU for as long as they need to use it, until they're done. We say this maximizes throughput because if you use a stopwatch to determine the amount of time required to complete a fixed queue of tasks, the strategy which maximizes throughput would result in the lowest elapsed time. This is a performance trap, however, because in a truly throughput-optimal approach, your application will be entirely unresponsive while it works its way through its initial work queue, and only after it is complete will it accept new tasks. Conversely, an optimally-fair approach would try to handle new incoming work as quickly as possible, *deprioritizing* old work and thus requiring more time to complete the full set of tasks. In a sense, this is a less efficient strategy, but the application remains responsive throughout.

> Fairness is a form of *prioritization*. You are prioritizing the responsiveness of your application over its raw performance.

When compute-bound work is so expensive that it cannot be organically prioritized together with other work without affecting the responsiveness of the application (i.e. triggering starvation issues), the only remaining option is to prevent that work from consuming all available compute resources. This can be accomplished in several ways, but the two most common strategies are `Semaphore` and `Queue`.

Let's assume that we need to execute one `expensiveThing` per inbound request, and we expect to receive many such requests. Let's further assume that `expensiveThing` is some indivisible compute-bound task, such as signing a JWT, meaning that we cannot split it into multiple `IO`s. We definitely want to wrap `expensiveThing` in `cede`s, but that won't be enough on its own. If we simply allow `expensiveThing` to run wild, then every single compute worker thread will spend most of its time inside of `expensiveThing` (which will show up on the thread dump and async profiler results). A global `Semaphore` is an easy way to ensure that only up to *n* `expensiveThing`s may be running at any point in time, where *n* can be set to something less than the number of available compute threads (which is to say, the number of CPUs).

```scala
object Throttling extends IOApp.Simple {

  val run =
    for {
      // starting with workers / 2 is a good rule of thumb
      throttle <- Semaphore[IO](Math.max(computeWorkerThreadCount / 2, 1))

      _ <- acceptRequest.toResource
        .flatMap(r => handleRequest(throttle, r).background)
        .useForever
    } yield ()

  def handleRequest(throttle: Semaphore[IO], r: Request): IO[Unit] = {
    val fairThing = IO.cede *> expensiveThing.guarantee(IO.cede)
    throttle.permit.use(_ => fairThing)
  }

  def acceptRequest: IO[Request] = ???
}
```

The above shows a fairly typical (albeit very simplified) network server application which handles many requests concurrently, each of which runs `expensiveThing` appropriately wrapped in `cede`s. However, *before* the handlers run `expensiveThing`, they first acquire a permit from `throttle`, which prevents more than `computeWorkerThreadCount / 2` simultaneous `expensiveThing`s from hogging the available CPU. **This will slow down the application.** It will also keep it responsive.

The key here is that, if new requests arrive from `acceptRequest`, there will be compute threads available to pick up those tasks, accept the request, perform whatever handshaking is required, and get things rolling for the handler. This is the essence of *fairness* and responsiveness.

In practical microservice terms, this would translate into higher p50 latencies but tighter p99 latencies ("tighter" in the sense that the p99 and the p50 would be much closer together, and the p99 would likely be lower as a consequence).

This strategy works quite well for any `expensiveThing` which must be executed on a per-request (or request-ish thing) basis. If `expensiveThing` is actually a *background* action that can be run concurrently with "real work", it may be more appropriate to rely on a bounded `Queue`:

```scala
object Backgrounding extends IOApp.Simple {

  val run = {
    val rsrc = for {
      // usually, size this based on memory footprint
      work <- Queue.bounded[IO, IO[Unit]](8096).toResource
      // create a worker which just executes whatever is on the queue
      _ <- work.take.flatMap(_.voidError).foreverM.background

      _ <- acceptRequest.toResource.flatMap(r => handleRequest(work, r).background)
    } yield ()

    rsrc.useForever
  }

  def handleRequest(work: Queue[IO, IO[Unit]], r: Request): IO[Unit] = {
    val fairThing = IO.cede *> expensiveThing.guarantee(IO.cede)
    work.offer(fairThing) *> respond(r)
  }

  def respond(r: Request): IO[Unit] = ???
  def acceptRequest: IO[Request] = ???
}
```

In this case, the `respond` function (which is presumably *not* expensive) is what is doing the work of responding to `Request`, while `expensiveThing` is happening in the background and doesn't block the response. Only one `expensiveThing` is running at a time, and in the event that we're having a hard time keeping up, we might end up waiting for the work queue to free up some space, but this will definitely prevent `expensiveThing` from eating up all of your available CPU resources, and it will also ensure that most requests receive a response very quickly.

Note that you can generalize this a bit if desired by increasing the number of workers reading from the `Queue`:

```scala
val run = {
  val rsrc = for {
    // usually, size this based on memory footprint
    work <- Queue.bounded[IO, IO[Unit]](8096).toResource

    // create several workers which just execute whatever is on the queue
    _ <- work.take
      .flatMap(_.voidError)
      .foreverM
      .background
      .replicateA_(computeWorkerThreadCount / 2)

    _ <- acceptRequest.toResource.flatMap(r => handleRequest(work, r).background)
  } yield ()

  rsrc.useForever
}

// ...
```

This will create `computeWorkerThreadCount / 2` workers, meaning that up to `computeWorkerThreadCount / 2` simultaneous `expensiveThing`s might be running at any point in time, but no more than that. Additionally, since this is an independent work queue, responses are never blocked.

At this point, though, it may be helpful to bring in [Fs2](https://fs2.io), since these types of patterns are much safer and simpler with that library than base Cats Effect.

### Not Enough CPUs

One of the most insidious trends in modern server-deployed software is the strategy of squeezing application resources down to the bare minimum, wrapping those applications up in containers, and attempting to recover the lost capacity by replicating a large number of instances. While there are absolutely good reasons to containerize applications and design them to be deployed in a replicated topology, abstracted from the underlying host systems, it is very much possible to have too much of a good thing.

In particular, it is very common to attempt to allocate only a single CPU (or less!), to an application. This in turn means that the application *itself* has too few compute resources to schedule across, which creates bottlenecks. Effectively, this forces the underlying system (which is to say, the operating system kernel) to be the *only* form of scheduling, making systems like Cats Effect much less relevant.

As you might expect from this kind of high-level description, exceptionally CPU-constrained deployments interact *very* poorly with Cats Effect. The reason for this often comes down to the number of threads that a normal application can expect to have active. For purposes of concreteness, let's assume that the application container has been assigned 2 vCPUs:

- 2 compute worker threads
- 1 timer dispatch thread
- 1 asynchronous I/O dispatch thread
- 2 blocking threads
- 1 JIT thread
- 1 GC thread
- 1 main thread

This is all assuming that there are no additional "rogue threads" stemming from third-party libraries. For example, the AWS SDK v2 allocates its own thread pools, which creates *even more* threads which aren't in the above list.

Even without the influence of third-party libraries and ad hoc thread pools, there are roughly 9(!) threads that the OS kernel must schedule onto just 2 vCPUs. Of those 9 threads, only 2 are doing actual compute-bound work, while the others are handling things like blocking I/O, timer dispatching, JIT, etc, but they still affect timeslicing and contend for interrupts.

More rigorously, the compute contention factor is 78% (`1 - 2/9`), which is incredibly high. This type of deployment configuration will almost always result in starvation, and since Kubernetes "best practices" often steer you in the direction of these types of extreme constraints, this represents a *very* common source of production starvation warnings.

Compare this to a more relaxed scenario in which each application container receives 8 vCPUs (and presumably, the overall cluster is scaled down by 75%):

- 8 compute worker threads
- 1 timer dispatch thread
- 1 asynchronous I/O dispatch thread
- 2 blocking threads
- 1 JIT thread
- 1 GC thread
- 1 main thread

Now the compute contention factor is just 47%, since the number of compute workers is that much higher. This is still a relatively high contention factor, but it's likely to be more in the realm of reasonable, particularly since most of these threads are idle. Increasing to 16 vCPUs would decrease the contention factor to just over 30%, at which point we can pretty safely say that it has become irrelevant.

This suggests a relatively general axiom:

> Cats Effect applications in replicated deployments will generally benefit from going slightly "taller" and not quite as "wide".

Backing up to first principles, this actually *makes sense*. The whole point of Cats Effect is to provide a userspace scheduling mechanism for application work, precisely because userspace schedulers are able to be more aggressively optimize for application workloads (and *particularly* asynchronous I/O). For a high-level discussion of why this is the case, both at the hardware and at the kernel level, please see [the following presentation by Daniel Spiewak](https://www.youtube.com/watch?v=PLApcas04V0).

The problem with "going wide" is it restricts the resources available within userspace (preventing a userspace scheduler from having meaningful impact) while delegating the overall scheduling problem to the kernel (which is much less efficient). This type of strategy makes a lot of sense when the userspace scheduler is very limited in nature and perhaps not as well optimized as the kernel scheduler, but it is simply counterproductive when the userspace scheduler is *more* optimized than the one that is in the kernel. This is the case with Cats Effect.

Of course, it's never as simple as doubling the number of vCPUs and halving the number of instances. Scaling is complicated, and you'll likely need to adjust other resources such as memory, connection limits, file handle counts, autoscaling signals, and such. Overall though, a good rule of thumb is to consider 8 vCPUs to be the minimum that should be available to a Cats Effect application at scale. 16 or even 32 vCPUs is likely to improve performance even further, and it is very much worth experimenting with these types of tuning parameters.

#### Not Enough Threads - Running in Kubernetes

One cause of "not enough threads" can be that the application is running inside kubernetes with a `cpu_quota` not configured. When the cpu limit is not configured, the JVM detects the number of available processors as 1, which will severely restrict what the runtime is able to do.

This guide on [containerizing java applications for kubernetes](https://learn.microsoft.com/en-us/azure/developer/java/containers/kubernetes#understand-jvm-available-processors) goes into more detail on the mechanism involved.

**All Cats Effect applications running in kubernetes should have either a `cpu_quota` configured or use the jvm `-XX:ActiveProcessorCount` argument to explicitly tell the jvm how many cores to use.**

### Too Many Threads

In a sense, this scenario is like the correlated inverse of the "Not Enough CPUs" option, and it happens surprisingly frequently in conventional JVM applications. Consider the thread list from the previous section (assuming 8 CPUs):

- 8 compute worker threads
- 1 timer dispatch thread
- 1 asynchronous I/O dispatch thread
- **2 blocking threads**
- 1 JIT thread
- 1 GC thread
- 1 main thread
- **some unknown number of rogue threads**

The two **bolded** items are worth staring at. For starters, there is no particular guarantee that there will be two and exactly two blocking threads. After all, the pool is unbounded, and it starts with none. So there could be as few as zero, and as many as… well, thousands. That second one is the problem, which we will come back to.

The "rogue threads" case is also worth understanding. In most JVM libraries, even those written in an asynchronous style, there is an implicit assumption that it is fine to just spin up new thread pools at will. It is relatively rare for a library to take an `Executor` as part of its initialization, and even when it does, it's usually not the default way in which it is initialized. For that reason, the JVM ecosystem as a whole has had a tendency to be quite profligate with its threads, which in turn means that the number of "rogue threads" is likely to be quite high in many applications. The AWS SDK is a decent example of exactly such a library, and the ecosystem of derivative libraries which build around it perpetuate this pattern.

Note that even the Typelevel ecosystem is not immune to this effect. If you're using the Blaze backend with Http4s (either client or server), you will end up with *at least* 8 rogue threads (if you have 8 CPUs), and possibly more. In theory, these threads sort of take the place of the one async I/O dispatch thread, but it's quite a few more threads than are actually needed.

This situation creates a significant amount of kernel scheduler contention, both in the form of timeslice granularity and frequent context shift penalties. Timeslicing is the mechanism used by all kernel thread schedulers to ensure that all threads have fair access to the CPU. You as the application author generally want timeslices to be as large as possible, since every timeslice boundary involves preemption and a lot of associated costs at the processor level. Unfortunately, every additional thread decreases the timeslice size proportionally, meaning that a large number of threads degrades performance for the rest of the process (and in fact, the rest of the *system*), even when those threads are entirely idle.

Context shifting is even worse, though. When the number of active (i.e. doing actual compute work) threads is less than or equal to the number of CPUs, then those threads do not contend with each other, meaning that they can all run essentially uninterrupted from start to finish. However, when the number of active threads is *greater* than the number of CPUs, then some or all of those threads will need to share time on their CPU with other active threads.

This situation is extremely bad for performance because unrelated threads are unlikely to operate on shared memory segments. This becomes a problem every time the kernel swaps between two threads on the same CPU, since the incoming thread must fully flush the L1, L2, and most of the L3 cache, paging out the data used by the outgoing thread and replacing it with its own working set. This operation is so expensive that it can come to entirely dominate an application's runtime, effectively making the same CPU much, much slower because of how inefficiently tasks are being scheduled.

These two things, particularly taken together, can be sufficiently impactful as to create a starvation scenario detectable by the starvation checker. To be clear, this is a case of *legitimate* starvation: it is happening because your application is choking on too many threads and thus running so slowly that its latency is increasing beyond the threshold.

The solution here is usually to try to tame the number of active threads. Here again, a thread dump (<kbd>Ctrl</kbd>-<kbd>\\</kbd>) or an async profiler snapshot are the best places to start. It may also be useful to look at the `ComputePoolSamplerMBean` on your running production application using JMX. In particular, the `getBlockedWorkerThreadCount` metric will tell you how many blocking worker threads are currently allocated. In general though, most JVM libraries are fairly good at naming their threads with some informative prefix, and you can usually guesstimate your way to a reasonable idea of how many threads you have at steady-state and where they're coming from.

The goal is to align the number of *active* threads to the number of CPUs while also maximizing the number of Cats Effect compute threads. In an ideal world, this would mean that the compute pool would be sized precisely to the number of CPUs and no other threads would exist, but pragmatically that almost never happens.

The first task is to figure out whether threads *other* than your `io-compute` threads are doing "real work". Are these threads just parked or blocked all of the time, or are they actually doing real compute on the CPU? The former would be indicative of things like the threads which handle `IO.blocking`, or event dispatch threads for timers or asynchronous I/O. These types of threads are not *good* to have in large numbers, but they're far less harmful than threads which are actively doing a lot of work.

You can determine this again with thread dumps and the async profiler. If you are in a situation where all of the non-`io-compute` threads spend most of their time in a blocked or parked state, then you can move on to the next stage of tuning (minimizing the number of inactive threads). If, however, you have identified a number of threads which *are* doing significant work, then you're going to need to take some prerequisite steps.

#### Aligning Active Threads

Let's say that your investigation has revealed the following situation:

- 8 compute worker threads (i.e. `io-compute-`…)
- 8 active rogue threads (threads doing real compute elsewhere, not just blocking)
- 12 inactive parking threads (e.g. `io-blocking`)

The sum of the first two counts really needs to be precisely the number of CPUs, while at the same time trying to ensure that the first count is as high as possible. The *best* way to achieve this is to try to get the source(s) of the active rogue threads to use the same threads as `IO` for compute tasks.

This can be accomplished in some cases by using `IO.executionContext` or `IO.executor`. In order to use this approach, *both* of the following must hold:

- The source of the rogue threads (e.g. another library) must have some initialization mechanism which accepts an `ExecutionContext` or `Executor`
- The source of the rogue threads must not ever *block* on its rogue threads: they must only be used for compute
  - The exception to this is if the library in question is a well-behaved Scala library, often from the Akka ecosystem, which wraps its blocking in `scala.concurrent.blocking(...)`. In this case, it is safe to use the Cats Effect compute pool, and the results will be similar to what happens with `IO.blocking`

Determining both of these factors often takes some investigation, usually of the "thread dumps and async profiler" variety, trying to catch the rogue threads in a blocked state. Alternatively, you can just read the library source code, though this can be very time consuming and error prone.

If you are able to fully consolidate the active rogue threads with the Cats Effect compute threads, then congratulations, you can move onto the next step! More likely though you will either be unable to consolidate the rogue threads (often due to poor or uncertain blocking semantics) or you will only be able to *partially* consolidate.

Let's imagine for the sake of example that you were able to partially consolidate: 6 of the 8 active rogue threads are now consolidated with Cats Effect, but 2 remain. In this case, you have to try to minimize and make space for the remaining threads using configuration. If the library spawning these rogue threads is configurable, try to reduce the 2 to a 1 by passing some configuration parameters which reduce the size of its internal thread pool. This is not *always* possible, but it is often enough that it's worth looking for. Note that you won't be able to use this approach to go from two to zero, but you can at least reduce down to one.

Now you have 8 compute worker threads and 1 active rogue thread. You need these two numbers to sum to the number of CPUs while keeping as many compute worker threads as possible, and unfortunately you have run out of options to reduce the active rogue thread count. In this case, you have to simply reduce the number of Cats Effect compute workers. This can be done with the following override in your `IOApp`:

```scala
override def computeWorkerThreadCount =
  Math.max(2, super.computeWorkerThreadCount - 1)
```

(it's generally best practice to keep at least 2 compute workers, even if you only have a single CPU, since the one CPU case is already so very very bad for performance that having an extra thread doesn't really *hurt*, and it can save you from some weird degenerate deadlock cases)

At this point, you have successfully brought down your number of active threads to align with your CPU. If you're still seeing starvation warnings, it is time to move onto minimizing the *inactive* threads.

#### Minimizing Inactive Threads

Inactive threads can come from a lot of different sources, including inside of Cats Effect itself! For example, every Cats Effect application (prior to version 3.5) has a single timer dispatch thread, allocated *in addition* to its compute threads, which just manages evaluation of `IO.sleep` effects.

Unfortunately, because inactive threads can come from a lot of different sources, it's difficult to give blanket advice on how to minimize them other than "try really hard". Large numbers of inactive threads sap performance both due to timeslicing granularity and because they all represent independent GC roots. For this reason, one of the most obvious early indicators of runaway thread counts (even inactive threads) is often exceptionally high GC pause times!

One source of inactive threads that *can* be discussed in this space is `IO.blocking`/`IO.interruptible`. These constructors are designed to wrap around side-effects which hard block a thread, and thus they function by allocating (or reusing) an extra thread within the Cats Effect runtime. In pathological scenarios, this can result in hundreds or even *thousands* of excess threads within the runtime, all spending their time in a blocked state before yielding back to the compute pool. While this is absolutely better than the alternative (directly starving the compute pool), it's still not *good*.

Realistically, you can't do *that* many concurrent blocking operations before it starts to become a performance problem. For that reason, it is worth using techniques similar to the ones we examined in the **Restrict Work** section: a global `Semaphore` and/or a concurrent work `Queue`. If you do take the `Semaphore` approach, a good default to start with is something like 8x the number of CPUs as the hard limit for the number of concurrently blocking tasks (i.e. `Semaphore[IO](computeWorkerThreadCount * 8)`), but this genuinely varies a lot depending on your use-case, so be sure to experiment and measure carefully.

Note that one *very* common source of runaway blocking workers is *logging*. Most loggers rely on blocking I/O under hood, simply because most loggers delegate to stderr or some other file-based source, and file I/O is blocking by nature. This is quite ironic given that most *production* logging systems end up shunting logs over to a separate server via an asynchronous network call (often performed by an application sidecar). If at all possible (and if relevant), try to move this asynchronous logging call *into* your application space, avoiding the indirection through the blocking I/O.

If this is not possible, the next-best approach is to rely on a circular buffer. Logging should always be asynchronous in nature, and thus adapting the `Queue.bounded` work queue approach from earlier to use `Queue.circularBuffer` results in a very robust and scalable logging mechanism which can limit the number of blocking workers devoted to logging to just one, dramatically reducing the load. This can also resolve ancillary issues within your application related to heavy log traffic, though the cost is that sometimes log messages may be dropped when under high load (this is a relatively standard tradeoff for logging and other application metrics).

### Burst Credit Contention

> This scenario is specific to Kubernetes, Amazon ECS, and similar resource-controlled containerized deployments.

In many Docker clusters, it is relatively standard practice to over-provision CPU credits by some factor (even 100% over-provisioning is quite common). What this effectively means is that the container environment will promise (or at least *allow*) *m* applications access to *n* vCPUs each, despite only *(m * n) / (1 + k)* CPUs being physically present across the underlying hardware cluster. In this equation, *k* is the over-provisioning factor, often referred to as "burst credits".

This is a standard strategy because many applications are written in a fashion which is only loosely coupled to the underlying CPU count, to a large degree because many applications are simply not optimized to that extent. Cats Effect *is* optimized to take advantage of the precise number of underlying CPUs, and well-written Cats Effect applications inherit this optimization, meaning that they are inherently much more sensitive to this hardware factor than many other applications.

To make matters worse, there is a fundamental assumption which underlies the notion of burst credits: load patterns across applications on the same cluster must be *asymmetrical*. If all applications attempt to access their burst credits simultaneously, the over-provisioning factor dominates and these applications will heavily contend for resources they were *promised* which don't actually exist. The metaphor of an airline overselling seats is apt: if a lot of people miss their flight, then it's fine and the airline comes out ahead, but if everyone who bought a seat shows up, then a lot of people are getting bumped to the next flight.

**In high-scale replicated backend servers, symmetric burst patterns are the *common* case.** In other words, in most high-scale applications, either everyone shows up for their flight, or no one does, but it is very rare to go half-and-half. For this reason, burst credits are a pretty bad idea *in general*, but they're particularly bad for runtimes like Cats Effect which aggressively take full advantage of the underlying hardware.

The solution is to eliminate this over-provisioning. If a scheduled container is promised *n* vCPUs, then those physical CPUs should be reserved for that container as long as it is active, no more and no less. This cluster tuning advice interacts particularly well with some advice from earlier in this document: Cats Effect applications benefit a lot from going *taller* rather than *wider*.

As a very concrete example of this, if you have a cluster of 16 host instances in your cluster, each of which having 64 CPUs, that gives you a total of 1024 vCPUs to work with. If you configure each application container to use 4 vCPUs, you can support up to 256 application instances simultaneously (without resizing the cluster). Over-provisioning by a factor of 100% would suggest that you can support up to 512 application instances. **Do not do this.** Instead, resize the application instances to use either 8 or 16 vCPUs each. If you take the latter approach, your cluster will support up to 64 application instances simultaneously. This *seems* like a downgrade, but these taller instances should (absent other constraints) support more than 4x more traffic than the smaller instances, meaning that the overall cluster is much more efficient.

#### Kubernetes CPU Pinning

Even if you have followed the above advice and avoided over-provisioning, the Linux kernel scheduler is unfortunately not aware of the Cats Effect scheduler and will likely actively work against the Cats Effect scheduler by moving Cats Effect worker threads between different CPUs, thereby destroying CPU cache-locality. In certain environments we can prevent this by configuring Kubernetes to pin an application to a gviven set of CPUs:
1. Set the [CPU Manager Policy to static](https://kubernetes.io/docs/tasks/administer-cluster/cpu-management-policies/#static-policy)
2. Ensure that your pod is in the [Guaranteed QoS class](https://kubernetes.io/docs/concepts/workloads/pods/pod-qos/#guaranteed)
3. Request an integral number of CPUs for your Cats Effect application

You should be able to see the CPU assignment updates reflected in the kubelet logs.


### Process Contention

All of the advice in this page is targeted towards the (very common) scenario in which your Cats Effect application `java` process is the only meaningfully active process on a given instance. In other words, there are no additional applications on the same server, no databases, nothing. If this is *not* the case, then a lot of the advice about having too many threads applies, but *worse*.

The worst possible scenario here is when your application server and one of its upstreams (such as a database) are on the same instance. When this happens, both the application and the database will be assuming full access to the underlying system CPU resources, and the result will be a lot of contention between each other. This will cause performance to degrade *quadratically*, since the contention penalties will be felt both on the application *and* on the upstream database. This is… bad.

In general, you should always try to assume that your applications are given dedicated instances. This is similar to the over-provisioning discussion in the previous section: if the application is promised 8 CPUs, it should have uncontended access to all 8 of those CPUs. Running two applications on the same instance, each promised full access to that instance's complement CPUs, is effectively the same as over-provisioning the CPU resource with all the associated problems.

This problem can be identified relatively simply with the relevant OS-level process metrics. `top` alone is usually a sufficient tool. If you see any process *other* than your application and the kernel taking a significant slice of CPU time, it suggests that you are creating some form of process contention.

The fix here is relatively simple: give your application a dedicated instance, without any additional active processes on the same instance.

### Timer Granularity

**This is the only false negative case.** In other words, this is the one root cause which is *not* legitimate starvation and instead corresponds to a measurement error.

Due to the fact that the starvation checker is effectively just measuring the difference between *intended* `sleep` time and *actual* `sleep` time, it is susceptible to errors caused by problems with `sleep` itself, rather than problems in the underlying compute scheduler. While these sorts of issues are rare, they can happen independently of compute-related issues, and when they occur they will create a legitimate false negative in the test. The correct response in this scenario is generally to disable the starvation checker.

This scenario can arise from two major root causes:

- Clamping
- Contention

Timer clamping is a phenomenon with which all JavaScript developers should be quite familiar. Ultimately, it arises when the underlying platform restricts the granularity of asynchronous timers to some relatively high-granularity target. For example, Firefox sometimes clamps timers (registered via `setTimeout`) to a minimum granularity of 10 milliseconds. In theory this doesn't seem like it should impact a test with a default threshold of 100 milliseconds, but at a minimum it makes the test proportionally less accurate.

Timer *contention* is a more complex problem that usually arises when your application has a very large number of very short timers. This can become visible in some extremely high-QPS network services (rule of thumb: anything over 10,000 QPS per instance). When this scenario arises, inefficiencies in the data structures used to manage active timers begin to dominate over the timers themselves, resulting in a sometimes-significant degradation in timer granularity. It is for this reason that many networking subsystems (such as Netty and even Akka) replace the default `ScheduledExecutorService` with an implementation based on Hash Wheel Ticks, which is less susceptible to this type of contention.

Since Cats Effect does not use HWT executors (at present), it is susceptible to this type of performance degradation, which will in turn affect the starvation checker. Do note that, in this scenario, the starvation checker is detecting a legitimate problem, but it's a much less serious problem than conventional compute starvation would be, and it does *not* mean that your application is necessarily suffering from high latency. When this root cause is in play, as with timer clamping scenarios, it is recommended that you simply disable the starvation checker.

## Configuring

Now that you've made it all the way to the end… Here's what you were probably Googling to find.

### Disabling

To entirely disable the checker (**not** recommended in most cases!), adjust your `IOApp` by overriding `config` in the following way:

```scala mdoc:silent
object MyMain extends IOApp {

  // fully disable the checker
  override def runtimeConfig =
    super.runtimeConfig.copy(cpuStarvationCheckInitialDelay = Duration.Inf)

  override def run(args: List[String]) = ???
}
```

> If you have any expectations or SLA around latency, you should not be disabling the checker, but instead should adjust its sensitivity to match your expectations. Only disable the checker if you either 1) do not have *any* latency-related requirements, or 2) have good reason to believe that your environment is such that timers are unreliable.

### Increasing/Decreasing Sensitivity

By default, the checker will warn whenever the compute latency exceeds 100 milliseconds. This is calculated based on the `cpuStarvationCheckInterval` (default: `1.second`) multiplied by the `cpuStarvationCheckThreshold` (default: `0.1d`). In general, it is recommended that if you want to increase or decrease the sensitivity of the checker, you should do so by adjusting the interval. Decreasing the interval results in a more sensitive check running more frequently, while increasing the interval results in a less sensitive check running less frequently:

```scala mdoc:silent
import scala.concurrent.duration._

object MyOtherMain extends IOApp {

  // relax threshold to 500 milliseconds
  override def runtimeConfig =
    super.runtimeConfig.copy(cpuStarvationCheckInterval = 5.seconds)

  override def run(args: List[String]) = ???
}
```

In general, you should plan on setting the sensitivity of the checker to something at or just above your application's SLA. If your application does not have a latency-related SLA, then it may be more appropriate to simply disable the checker.
