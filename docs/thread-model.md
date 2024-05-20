---
id: thread-model
title: Thread Model
---

Cats Effect is a powerful tool for creating and reasoning about highly
concurrent systems. However, to utilize it effectively it is necessary to
understand something about the underlying thread model and how fibers are
scheduled on top of it.

This section also discusses Cats Effect 2 as the comparison is instructive and
many of our users are already at least somewhat familiar with it.

## High-level goals

The high-level goals of threading are covered in detail by [Daniel's gist](https://gist.github.com/djspiewak/46b543800958cf61af6efa8e072bfd5c) so I'll
just give the executive summary. We are aiming for:

* A single thread pool of roughly the number of available processors for compute-based operations
   (depending on your application you may get better performance by leaving one or two cores
   free for GC, etc)
* An unbounded, cached threadpool for blocking operations
* 1 or 2 high-priority threads for handling asynchronous I/O events, the handling of which should
   immediately be shifted to the compute pool

The goal of this is to minimize the number of expensive thread context shifts
and to maximize the amount of time that our compute pool is doing useful work.

It is also worth noting that `scala.concurrent.ExecutionContext.global` is a
poor choice for your compute pool as its fork-join design assumes that there
will be blocking operations performed on it and hence it allocates more threads.
In addition, there is no way to stop libraries on your classpath from scheduling
arbitrary code on it so it is a very unreliable basis for your compute pool.

## The IO runloop

A simplified `IO` might look something like this:

```scala
sealed abstract class IO[A] {
  def flatMap[B](f: A => IO[B]): IO[B] = FlatMap(this, f)

  def unsafeRun(): A = this match {
    case Pure(a) => a
    case Suspend(thunk) => thunk()
    case FlatMap(io, f) => f(io.unsafeRun()).unsafeRun()
  }
}

case class Pure[A](a: A) extends IO[A]
case class Suspend[A](thunk: () => A) extends IO[A]
case class FlatMap[A, B](io: IO[B], f: B => IO[A]) extends IO[A]
```

Of course this has no error handling, isn't stacksafe, doesn't support asynchronous effects, etc
but it's close enough for illustrative purposes. The key thing to note is that `unsafeRun()`
is a tightly CPU-bound loop evaluating different layers of `IO`. The situation is just the same
when we evaluate the real `IO` via one of the `unsafeRunX` methods or as part of an `IOApp`.

### Fibers

Of course we tend to have many logical threads of execution in our applications.
Cats effect trivially supports this via lightweight `Fiber`s, each of which is
an instance of the `IO` runloop. These are run `m:n` on the OS-level threads (so
there is no direct mapping between fibers and threads) and can be created via
`IO#start`, as well as various combinators like `IO#race`. It is important to
note that this is [cooperative
multi-tasking](https://en.wikipedia.org/wiki/Cooperative_multitasking) (as
opposed to pre-emptive) so it is the responsibility of a fiber to yield control
of the CPU by suspending its runloop periodically. In practice this is rarely an
issue as fibers automatically yield at asynchronous boundaries (eg I/O) but it
does means that it is actually possible for a fiber to take control of a CPU
core and never give it back if it executes some heavily CPU-bound operations like

```scala
def calculate(data: Vector[BigInt]): IO[BigInt] =
  IO(longSubTaskA(data))
    .map(xs => longSubTaskB(xs))
    .map(ys => longSubTaskC(ys))
```

If you have such operations then you can insert a fairness boundary via `IO.shift`
(CE2 but has other potential side-effects) or `IO.cede` (CE3), which will give
another fiber an opportunity to run on the thread.

Note that the runloop-per-fiber model means that we obtain maximum performance
when all of our CPU threads are free to evaluate this runloop for one of our
`IO` fibers.

### Thread blocking

A direct consequence of the above is that running blocking code on our compute
pool is _very_ bad. If we're running on a node with 2 CPUs and we evaluate a
blocking call like `IO(Source.fromFile(path).getLines())` then for the duration
of that operation our capacity to evaluate `IO` fibers is _halved_. Run two such
operations at the same time and your application effectively stops until one of
those blocking calls completes.

The solution to this is to shift the execution of the blocking operation to our
unbounded, cached threadpool and then shift computation back to the compute pool
once the blocking call has completed.  We'll see code samples for this later as
it is quite different between CE2 and CE3.

### Fiber blocking (previously "Semantic Blocking")

Of course, we do also need the ability to tell fibers to wait for conditions to
be fulfilled. If we can't call thread blocking operations (e.g., Java/Scala builtin
locks, semaphores, etc.) then what can we do? It seems we need a notion of
_semantic_ blocking, where the execution of a fiber is suspended and control of
the thread it was running on is yielded. This concept is called "fiber blocking" in cats-effect.

Cats effect provides various APIs which have these semantics, such as
`IO.sleep(duration)`.  Indeed this is why you must never call
`IO(Thread.sleep(duration))` instead, as this is a thread blocking operation
whereas `IO.sleep` is only fiber blocking.

The building block for arbitrary semantic blocking is `Deferred`, which is a
purely functional promise that can only be completed once.

```scala
trait Deferred[F[_], A] {
  def get: F[A]

  def complete(a: A): F[Boolean]
}
```

`Deferred#get` is fiber blocking until `Deferred#complete` is called and
Cats Effect provides many more fiber blocking abstractions like
semaphores that are built on top of this.

## Summary thus far

So we've seen that best performance is achieved when we dedicate use of the compute pool
to evaluating `IO` fiber runloops and ensure that we shift _all_ blocking operations
to a separate blocking threadpool. We've also seen that many things do not need to
block a thread at all – cats effect provides fiber blocking abstractions for waiting
for arbitrary conditions to be satisfied. Now it's time to see the details of how we achieve
this in cats effect 2 and 3.

## Cats Effect 2

CE2 `IOApp` provides a fixed execution context sized to the number of available
cores for us to use for compute-bound work. This maintains a global queue of
runnables awaiting scheduling. Several abstractions are provided to facilitate
shifting work to other pools.

### Context shift

`ContextShift` is a pure representation of a threadpool and looks a bit like this:

```scala
trait ContextShift[F[_]] {

  def shift: F[Unit]

  def evalOn[A](ec: ExecutionContext)(fa: F[A]): F[A]

}
```

An instance of this will be backed by some thread pool. `IOApp`
provides an instance which is backed by the default compute pool it provides.

`evalOn` allows us to shift an operation onto another pool and have the
continuation be automatically shifted back eg

```scala
CS.evalOn(blockingPool)(
    IO(println("I run on the blocking pool"))
  ) >> IO(println("I am shifted onto the pool that CS represents"))
```

`shift` is a uni-directional shift of thread pool so that the continuation runs on the pool that
the `ContextShift` represents

```scala
IO(println("I run on some pool")) >> CS.shift >> IO(println("I run on the pool that CS represents"))
```

### Blocker

`Blocker` was introduced to provide an abstraction for our unbounded pool for blocking operations.
It relies upon `ContextShift` for its actual behaviour and is simply a marker for a threadpool
that is suitable for blocking operations.

```scala
trait Blocker {
  def blockOn[F[_], A](fa: F[A])(implicit cs: ContextShift[F]): F[A]
}

blocker.blockOn(IO(readFile)) >> IO(println("Shifted back to the pool that CS represents"))
```

`blockOn` behaves exactly like `ContextShift#evalOn` - the provided `fa` will be run on the
blocker's pool and then the continuation will run on the pool that `cs` represents.

The fact that it is an abstraction for our unbounded blocking pool means that it
should almost always be instantiated via `Blocker.apply[F]` which creates it
with an unbounded, cached threadpool and adds some thread naming for you to
identify them should you be unfortunate enough to be looking at thread dumps.

A common pattern in libraries for CE2 is to have an API which asks for a `Blocker`
and an implicit `ContextShift`

```scala
def api[F[_] : ContextShift](blocker: Blocker): F[Api]
```

In this case you _must_ provide the `ContextShift` given to you by `IOApp`
(unless you're absolutely sure you know what you're doing) as the expectation
of the library authors is that they can use that `ContextShift` to shift
execution back to the compute pool after performing any blocking operations on
the provided `Blocker`.

### Local reasoning

Unfortunately there are some problems with these abstractions - we lose the ability to reason
locally about what thread pool effects are running on.

```scala
def prog(inner: IO[Unit]): IO[Unit] =
  for {
    _ <- IO(println("Running on the default pool"))
    _ <- inner
    _ <- IO(println("Uh oh! Where is this running?"))
  } yield ()
```

The problem is that `inner` could be something like `randomCS.shift` in which
case the continuation (the second print) will be run on whatever thread pool
`randomCS` represents.

In fact, `shift` is _never_ safe for this reason and `evalOn` is only safe if
the `ContextShift` in implicit scope represents the threadpool that we were
running on before so that we shift back to where we were executing before.
Nested `evalOn` is also prone to non-intuitive behaviour - see [this
gist](https://gist.github.com/TimWSpence/c0879b00936f495fb53c51ef15227ad3) for
one such example.

What we need is the ability to locally change the threadpool with
the guarantee that the continuation will be shifted to the previous
pool afterwards. If you are familiar with `MonadReader`

```scala
trait MonadReader[F[_], R] {
  def ask: F[R_] //get the current execution context

  def local[A](alter: R => R)(inner: F[A]): F[A] //run an inner effect with a different execution
                                                 //context and then restore the previous
                                                 //execution context
}
```

then you might see that this has exactly the semantics we need, where
`local` is like `evalOn` in allowing us to locally change the
execution context, but it will be restored to the previous value afterwards.

### Auto-yielding

Auto-yielding is the automatic insertion of fiber yields into the runloop to
ensure that a single fiber does not hog a CPU core and is not supported in CE2
as yielding requires re-enqueuing the fiber on a global queue and waiting for it
to be re-scheduled. This is too expensive to be inserted automatically as the
global queue requires coordination between the CPU cores to access this shared
resource and will also result in CPU core-local caches being invalidated. If you
have a tight CPU-bound loop then you should insert `IO.shift` where appropriate
whilst ensuring that the implicit `ContextShift` is the one that represents the
current execution context (so you don't accidentally shift the execution to
another pool).

### Obtaining a handle to the compute pool

Another unfortunate wart is that it is very difficult to obtain a handle to `IOApp's`
compute pool. This can be worked around with `IOApp.WithContext` but it is somewhat
clunky, especially if you want to instantiate the same threadpool as `IOApp` would
otherwise instantiate.

```scala
object Main extends IOApp.WithContext {

  override protected def executionContextResource: Resource[SyncIO, ExecutionContext] =
    instantiateSomeCustomThreadpoolHere()

  override def run(args: List[String]): IO[ExitCode] = {
    val computeEC = executionContext
    program(computeEC)
  }
}
```

## Cats Effect 3

The good news is that CE3 fixes these things and makes other things nicer as well! :)
Notably, `ContextShift` and `Blocker` are no more.

### Async

CE3 introduces a re-designed typeclass `Async`

```scala
trait Async[F[_]] {
  def evalOn[A](fa: F[A], ec: ExecutionContext): F[A]

  def executionContext: F[ExecutionContext]
}
```

which has exactly the `MonadReader` semantics we discussed above. Note that the
execution shifts back to the threadpool defined by `Async#executionContext`.

Also note that `Async[IO].executionContext` in `IOApp` will give us a handle to
the compute pool without the `WithContext` machinery.

### Blocking

CE3 has a builtin `blocking` which will shift execution to an internal
blocking threadpool and shift it back afterwards using `Async`.

This means that we can simply write

```scala
IO.println("current pool") >> IO.blocking(println("blocking pool")) >> IO.println("current pool")
```

There is also a similar operation `interruptible` which shifts to the blocking
pool but will also attempt to cancel the operation using `Thread#interrupt()` in
the event that the fiber is canceled.

### Work-stealing pool

CE3 also has a very exciting custom work-stealing threadpool implementation. This has
numerous benefits over the `FixedThreadpool` used in CE2:

* It maintains a work queue per core rather than a single global one so contention
   is dramatically reduced, especially with lots of cores
* This means that we can implement thread affinity, where a fiber that yields is most
   likely to be re-scheduled on the same thread. This makes yielding much cheaper
   as if the fiber is immediately re-scheduled we don't even have to flush CPU caches
* Consequently we can support auto-yielding where a fiber will insert an `IO.cede`
   every fixed number of iterations of the runloop, stopping a rogue cpu-bound fiber
   from inadvertently pinning a CPU core

## And that's it!

CE3 drastically simplifies threadpool usage and removes a number of significant
gotchas, whilst significantly improving performance.
