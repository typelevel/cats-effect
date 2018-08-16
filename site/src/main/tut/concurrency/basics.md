---
layout: docsplus
title:  "Concurrency Basics"
position: 2
---

## Introduction

Concurrency is not an easy topic, there are a lot of concepts involved and the vocabulary might be hard to google. 
This post intends to gather and explain some of the most important ideas and serve as a reference point for
understanding the basics of concurrency. 
It is focused on using Scala with libraries in Cats-Effect ecosystem.

## Dictionary

### Parallelism 
Using multiplicity of computational resources (like more processor cores) to perform a computation faster, 
usually executing at the same time.

Example: summing a list of Integers by dividing it in half and calculating both halves in parallel.

Main concern: efficiency.

### Concurrency
Multiple threads of control, the user sees their effects interleaved. It doesn't have to be multithreaded - we can 
write concurrent applications on single processor using methods such as event loops.

Example: Communicating with external services through HTTP.

Main concern: interaction with multiple, independent and external agents.

### CPU-bound task 
Operation that mostly requires processor resources to finish its computation.

### IO-bound task
Operation that mostly does I/O and it doesn't depend on your computation resources, 
e.g. waiting for disk operation to finish or external service to answer your request.

### non-terminating task
Task that will never signal its result but it doesn't mean its blocking any threads or will execute forever.

```scala
IO.never *> IO(println("done"))
```

It will never print "done" - it will silently finish instead and will be garbage collected at some point in the future.

### logical thread

JVM Thread, this is what we create with `new Thread()`. It is possible to create many logical threads.

### native thread

Operating System' thread, extremely scarce resource, usually the number of processor cores.

## Threads

### Threading (on JVM)

Threads in JVM map 1:1 to operating systems' native threads. It doesn't mean we cannot have more logical threads 
in our programs but if we have 4 cores we can execute up to 4 threads at the same time. 
Others have to wait for their turn. 

If we try to run too many threads at once we will suffer because of many **context switches**. 
Before any thread can start doing real work the OS needs to store state of earlier task and restore the state 
for the current one. This cleanup has nontrivial cost. The most efficient situation for CPU-bound tasks 
is when we execute as many logical threads as the number of available native threads.

For above reasons, synchronous execution can perform much better than parallelizing 
too much which won't make your code magically faster.

Overhead of creating or jumping threads is often bigger than the speedup so make sure to benchmark. 
Also, remember that threads are scarce resource on JVM. If you exploit them at every opportunity 
it may turn out that your most performance critical parts of the application suffer because other part is 
doing a lot of work in parallel, taking precious native threads.

### Thread Pools

Creating a **Thread** has a price to it. The overhead depends on specific JVM and OS but it involves 
several activities from both of them so making too many threads for short-lived tasks is very inefficient .
It may turn out that process of creating thread and possible context switches has higher costs than the task itself.
Furthermore, having too many created threads means that we can eventually run out of memory and that they are 
competing for CPU, slowing down entire application.

It is advised to use **thread pools** which by means of `Executor` (`ExecutionContext` in Scala) serve the purpose of 
managing execution of threads.

Thread pool consists of work queue and a connection to running threads. Every task (`Runnable`) to execute is 
placed in the work queue and the threads that are governed by given pool take it from there to do their work. 
In Scala, we avoid explicitly working with `Runnable` and use abstractions that do that under the hood 
(`Future` and `IO` implementations). Thread pools can reuse and cache threads to prevent some of the problems 
mentioned earlier.

### Choosing Thread Pool

We can configure thread pools in multiple ways, those are the main ones:

#### Bounded 

Limiting number of available threads to certain amount. Example could be `newSingleThreadExecutor` to execute 
only one task at the time or limiting number of threads to number of processor cores for CPU-bound tasks.

#### Unbounded 

No maximum limit of available threads. Note that this is dangerous because we could run out of memory by creating 
too many threads so it’s important to use cached pool (allowing to reuse existing threads) with `keepalive` time 
(to remove useless threads) and control number of tasks to execute by other means (backpressure, rate limiters). 

Despite those dangers it is still very useful for blocking tasks. In limited thread pool if we block 
too many threads which are waiting for callback from other (blocked) thread for a long time we risk 
getting deadlock that prevents any new tasks from starting their work.

For a bit more in-depth guidelines [read Daniel Spiewak's gist.](https://gist.github.com/djspiewak/46b543800958cf61af6efa8e072bfd5c)

### Blocking Threads

As a rule we should never block threads but sometimes we have to work with interface that does it. 
Blocking a thread means that it is being wasted and nothing else can be scheduled to run on it. 
As mentioned, this can be very dangerous and it's best to use dedicated thread 
pool for blocking operations. This way they won't interfere with CPU-bound part of application.

`cats.effect.IO` and `monix.eval.Task` provide `shift` operator which can switch computation to different thread pool.
If you need to execute blocking operation and come back consider using `ContextShift.evalOn` which is meant for this use case:

```tut:silent
import java.util.concurrent.Executors
import cats.effect.{ContextShift, IO}
import scala.concurrent.ExecutionContext
  
implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
val blockingEC = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
  
def blockingOp: IO[Unit] = ???
def doSth(): IO[Unit] = ???
  
val prog =
  for {
    _ <- contextShift.evalOn(blockingEC)(blockingOp) // executes on blockingEC
    _ <- doSth()                                     // executes on contextShift
  } yield ()
``` 

For more convenient tools for this pattern [see linebacker.](https://github.com/ChristopherDavenport/linebacker)

Other resource with good practices regarding working with blocked threads 
[is this section of Monix documentation.](https://monix.io/docs/3x/best-practices/blocking.html)

### Green Threads

There are more types of threads and they depend on the platform. One of them is
[*green thread*](https://en.wikipedia.org/wiki/Green_threads). The main difference 
between model represented by JVM Threads and Green Threads is that the latter aren't scheduled on OS level. They are
much more lightweight which allows starting a lot of them without many issues.

They are often characterized by [cooperative multitasking](https://en.wikipedia.org/wiki/Cooperative_multitasking) 
which means the thread decides when it's giving up control instead of being forcefully denied it like it happens on JVM.

This term is important for `cats.effect.IO` because with its' `Fiber` and `shift` design there are a lot of similarities
to this model. This will be explained in the next section.

## Thread Scheduling
Working with `cats.effect.IO` you should notice a lot of calls to `IO.shift` which is described 
in [Thread Shifting section in `IO` documentation](./../datatypes/io.html#thread-shifting)

This function allows to shift computation to different thread pool or simply send it to current `ExecutionContext` 
to schedule it again. This is often called introducing **asynchronous boundary**.

While the first use case is probably easy to imagine, the second one might be more confusing. 
It is helpful to actually understand what is happening behind the scenes during `shift`.

Essential term is **thread scheduling**. Since we can’t run all our threads in parallel all the time they 
each get their own slice of time to execute, e.g. interleaving with the rest of them so every thread has a chance to run. 
If it is time to change threads, the currently running thread is **preempted** - it saves its state and the context switch happens.

This is a bit different when using thread pools (`ExecutionContexts`) because they are in charge of 
scheduling threads from their own pool. If there is one thread running it won’t change until it terminates or 
higher priority thread is ready to start doing work. Note that `IO` without any shifts is considered one task 
so if it’s infinite `IO` it could hog the thread forever and if we use single threaded thread pool - nothing else 
will ever run on it! 

In other words - `IO` is executing synchronously until we call `IO.shift` or use function like `parSequence` which 
does it for ourselves. In terms of individual thread pools we can actually treat `IO` like **green thread** with 
[cooperative multitasking](https://en.wikipedia.org/wiki/Cooperative_multitasking) - instead of 
[forcefully preempting](https://en.wikipedia.org/wiki/Preemption_(computing)#PREEMPTIVE) thread 
we can decide when we yield CPU to other threads from the same pool by calling `shift`.

Calling `IO.shift` sends it to schedule again so if there are other `IO` waiting to execute they can have their chance. 
Likelihood of different threads advancing their work is called **fairness**.

Let's illustrate this:

```tut:silent
import java.util.concurrent.Executors
import cats.effect.{ContextShift, Fiber, IO}
import cats.syntax.apply._
import scala.concurrent.ExecutionContext

val ecOne = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
val ecTwo = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

val csOne: ContextShift[IO] = IO.contextShift(ecOne)
val csTwo: ContextShift[IO] = IO.contextShift(ecTwo)

def infiniteIO(id: Int)(implicit cs: ContextShift[IO]): IO[Fiber[IO, Unit]] = {
  def repeat: IO[Unit] = IO(println(id)).flatMap(_ => repeat)

  repeat.start
}
```

We have two single threaded `ExecutionContexts` (wrapped in [ContextShift](./../datatypes/contextshift.html))
and a function that will run `IO` forever printing its identifier. 
Note `repeat.start` and return type of `IO[Fiber[IO, Unit]]` which means that we run this computation in the background.
It will run on thread pool provided by `implicit cs: ContextShift[IO]` which we will pass directly:

```scala
val prog =
  for {
    _ <- infiniteIO(1)(csOne)
    _ <- infiniteIO(11)(csTwo)
  } yield ()

prog.unsafeRunSync()
```
It will never print `11` despite using `.start`! 
Why? `ExecutionContext` `ecOne` executes its `IO` on the only thread it has but it needs to wait for its 
completion before it can schedule the other one.

How about two thread pools?

```scala
val prog =
  for {
    _ <- infiniteIO(1)(csOne)
    _ <- infiniteIO(11)(csOne)
    _ <- infiniteIO(2)(csTwo)
    _ <- infiniteIO(22)(csTwo)
  } yield ()

prog.unsafeRunSync()
```

Now it will keep printing both `1` and `2` but neither `11` nor `22`. What changed? 
Those thread pools are independent and interleave because of thread scheduling done by Operating System.

Let's get it right:
```scala
def infiniteIO(id: Int)(implicit cs: ContextShift[IO]): IO[Fiber[IO, Unit]] = {
  def repeat: IO[Unit] = IO(println(id)).flatMap(_ => IO.shift *> repeat)
  
  repeat.start
}

val prog =
  for {
    _ <- infiniteIO(1)(csOne)
    _ <- infiniteIO(11)(csOne)
    _ <- infiniteIO(2)(csTwo)
    _ <- infiniteIO(22)(csTwo)
  } yield ()

prog.unsafeRunSync()
```

Notice `IO.shift *> repeat` call - `*>` means that we execute first operation, ignore its result and then call `repeat`. 
Now everything is fair, we can see each of those numbers printed on the screen. 
Calling `IO.shift` fixed the problem because when currently running `IO` was sent to schedule again it gave opportunity 
to execute the other one.

It probably sounds quite complex and cumbersome to keep track of it yourself but once you understand fundamentals 
this explicity can be a great virtue of `cats.effect.IO`. Knowing what exactly happens in concurrent scenarios 
in your application just by reading the piece of code can really speedup debugging process or even allow to 
get it right the first time.

Fortunately `cats.effect.IO` doesn't always require to do it manually and operations like `race`, `parMapN` 
or `parTraverse` introduce asynchronous boundary at the beginning but if you have limited thread pool and long 
running tasks keep fairness in mind. 

Scala's `Future` is optimized for fairness, doing `shift` equivalent after each `map` or `flatMap`.
We wouldn't have the problem described above but doing it too much results in putting a lot of pressure on 
scheduler causing low throughput. In typical purely functional programs we have many `flatMaps` because our 
entire application is just one big `IO` composed of many smaller ones. Constant shifting is not feasible 
but there's always the option to do it if our application has strict latency requirements.

If you are looking for less manual work - `monix.eval.Task` is great middleground which by default shifts tasks 
automatically in batches preserving both great throughput and decent latency off the shelf and exposes 
very rich configuration options if you have more advanced use case.

## Asynchronous / Semantic blocking
Sometimes we use term **semantic blocking** or **asynchronous blocking** which is different than blocking thread. 
It means that we "suspend" our IO/Task waiting for some action to happen (e.g. `Deferred.get` waits until the 
result is available) but it doesn't block threads - other `IO` is free to execute on the thread in the meantime.
This is further explained [in Fabio Labella's comment.](https://github.com/typelevel/cats-effect/issues/243#issuecomment-392002124)

It is important to recognize that not all I/O operations are blocking and need to execute on dedicated thread pool. 
For instance we can have HTTP requests using non-blocking client such as http4s with Blaze which is 
definitely network I/O but without any blocking involved so it's free to execute on "normal" pool.