---
id: spawn
title: Spawn
---

This typeclass provides a lightweight `Thread`-like abstraction, `Fiber`, which can be used to implement parallel evaluation semantics. Much like `Thread`, `Fiber` is not often directly useful in *user* code, but instead is best when used as an implementation detail for higher-level functionality, such as the [`Parallel`](https://typelevel.org/cats/typeclasses/parallel.html) typeclass in Cats.

Fibers are exceptionally lightweight, *semantic* threads of execution. There's a lot to unpack in that sentence, so we'll take it one step at a time. `Thread` is a somewhat infamously expensive construct on the JVM. Even on an extremely beefy machine, you really can't have more than a few thousand of them before the garbage collector begins to bog down and the context switch penalties on the CPU become prohibitively high. In practice though, the situation is even worse. The *optimal* number of threads is usually just a little over the number of processors available to the host operating system, while the optimal number of *concurrent semantic actions* is likely to be exceptionally high. For example, an application implementing a microservice would likely desire at least one concurrent action per request at any given point in time, but if the number of concurrent actions is limited to the number of available threads (which is in turn limited to the number of available processors!), then the service is [not likely to scale](http://tomcat.apache.org) particularly well.

Clearly there is a mismatch here. Applications conventionally resolve this through the use of thread pools and other, extremely-manual techniques, and while these work reasonably well, they're quite easy to get wrong and very limited in terms of the functionality which can be built on top of them. A better abstraction is needed, one which allows framework and user code to simply spawn semantic actions as-needed (e.g. to handle an incoming request), while the underlying runtime takes care of the messy details of mapping those actions to real kernel threads in an optimal fashion.

This is what fibers achieve:

```scala mdoc
import cats.effect.{MonadCancel, Spawn}
import cats.effect.syntax.all._
import cats.syntax.all._

trait Server[F[_]] {
  def accept: F[Connection[F]]
}

trait Connection[F[_]] {
  def read: F[Array[Byte]]
  def write(bytes: Array[Byte]): F[Unit]
  def close: F[Unit]
}

def endpoint[F[_]: Spawn](
    server: Server[F])(
    body: Array[Byte] => F[Array[Byte]])
    : F[Unit] = {

  def handle(conn: Connection[F]): F[Unit] =
    for {
      request <- conn.read
      response <- body(request)
      _ <- conn.write(response)
    } yield ()

  val handler = MonadCancel[F] uncancelable { poll =>
    poll(server.accept) flatMap { conn =>
      handle(conn).guarantee(conn.close).start
    }
  }

  handler.foreverM
}
```

There's a *lot* going on in this example, but thanks to the power of functional programming, we can break it down into tiny chunks that we analyze and understand one at a time. Let's start at the very end:

```scala
handler.foreverM
```

Alright, so whatever `handler` happens to be, we're going to keep doing it *indefinitely*. This already seems to imply that `handler` is probably "the thing that handles a single request". Let's look at `handler` and see if that intuition is born out:

```scala
val handler = MonadCancel[F] uncancelable { poll =>
  poll(server.accept) flatMap { conn =>
    handle(conn).guarantee(conn.close).start
  }
}
```

We're using `uncancelable` from `MonadCancel` to avoid resource leaks in the brief interval between when we get a connection (`conn`) and when we set up the resource management to ensure that it is properly `close`d. Aside from that added verbosity, this is actually fairly concise. Guessing based on names, we can assume that `server.accept` is an effect (wrapped in `F`!) which produces a client connection whenever a new one is established. We then take this connection and pass it to the `handle` function, which presumably has our endpoint logic, and pair that logic up with a `guarantee` that the connection will be `close`d, regardless of the outcome of the handling.

Then the interesting bit happens: we call `.start` on this effect. Remember that a functional effect is not *running* (present tense), but rather a description of something that *will run* (future tense). So we can talk about `handle(conn).guarantee(conn.close)` as an expression without being worried about it running off and doing things outside our control. This gives us a lot of power. In this case, it gives us the power to take that effect and create a new fiber which will run it.

The `start` function takes an effect `F[A]` and returns an effect which produces a new `Fiber[F, E, A]`, where `E` is the error type for `F` (usually `Throwable`). The `Fiber` type is a *running* fiber: it is actually executing as soon as you have the `Fiber` instance in your hand. This means that it's running in the background, which is to say, it is a separate semantic thread.

On some platforms (such as the JVM), this `Fiber` might be mapped to a real kernel `Thread` and may in fact be running in parallel. On other platforms (such as JavaScript), it might be simply waiting for some event dispatcher to have availability. The nice thing about `Fiber` as an abstraction is we don't *really* need to care about this distinction: it represents a parallel semantic thread of execution, whether it runs in parallel *at this exact moment* or not is a concern for the runtime.

This also provides some marvelous benefits in terms of efficiency. `Fiber` tends to be incredibly lightweight. Cats Effect's `IO` implements fibers in roughly 128 bytes *per fiber*, and most other implementations are within the same order of magnitude. You can allocate literally tens of millions of fibers on a single JVM without causing any problems, and the runtime will sort out the best way to map them down to live threads in a fashion which is optimized for your specific platform and architecture.

This is how our example above is able to get away with `start`ing a new `Fiber` for every new connection: there's no reason *not* to! Fibers are so lightweight, we can just create as many as we need to get the job done, and the only real limit is memory.

As an aside, we often use the word "fiber" interchangeably with the phrase "semantic thread of execution", simply because the former is very much easier to say. It is also no less accurate: all functional effects represent at least one fiber, and each *step* of that fiber is another `flatMap`.

### Cancelation

Probably the most significant benefit that fibers provide, above and beyond their extremely low overhead and optimized runtime mapping, is the fact that, unlike JVM `Thread`s, they are *cancelable*. This means that you can safely `cancel` a running fiber and it will clean up whatever resources it has allocated and bring itself to a halt in short order, ensuring that you don't have errant processes running in the background, eating up resources that could have otherwise been released.

We can demonstrate this property relatively easily using the `IO` monad:

```scala mdoc
import scala.concurrent.duration._
import cats.effect.IO

for {
  target <- IO.println("Catch me if you can!").foreverM.start
  _ <- IO.sleep(1.second)
  _ <- target.cancel
} yield ()
```

This will print "`Catch me if you can!`" a nondeterministic number of times (probably quite a few!) as the `target` fiber loops around and around, printing over and over again, until the main fiber finishes sleeping for one second and cancels it. Technically, cancelation may not *instantaneously* reflect in the target fiber, depending on implementation details, but in practice it is almost always practically instant. The `target` fiber's execution is almost immediately halted, it stops printing, and the program terminates.

It is actually impossible to replicate this example with `Thread` without building your own machinery for managing cancelation (usually some shared `Boolean` which tracks whether or not you've been canceled). With `Fiber`, it is handled for you.

Even more importantly, this cancelation mechanism is the same one that is described by [`MonadCancel`](./monadcancel.md), meaning that all of the resource safety and `uncancelable` functionality that it defines can be brought to bear, making it possible to write code which is resource-safe even when externally canceled by some other fiber. This problem is nearly impossible to solve by any other means.

In practice, this kind of cancelation is often handled for you (the user) in the form of cleanup when unexpected things happen. For example, imagine the following code:

```scala mdoc:silent
import cats.syntax.all._

(-10 to 10).toList.parTraverse(i => IO(5f / i))
```

The `parTraverse` construct is a higher-level concurrency tool provided by Cats, ultimately backed by `Spawn` and `Fiber` behind the scenes. In this snippet, for each of the `Int`s within the `List`, we create a new `IO` which uses that value as a divisor under the float `5f`. The `IO` computes the result of this division, and since we're using a form of `traverse`, it will be evaluated and merged together into a single `List` inside of an outer `IO`. Thus, the result of this line is an `IO[List[Float]]`.

The `par` part of `parTraverse` means that, rather than performing each `IO` action in sequence (from left to right, as it happens), it will actually spawn a new fiber for each action and run them all *in parallel*. This is usually a much nicer way of doing concurrency than manually fiddling around with `start` and `cancel`. It's still `Fiber` under the surface, but the API is much higher level and easier to work with.

Of course, *one* of these divisions will fail and an exception will be raised. When this happens, the result of the whole evaluation is discarded and the `IO[List[Float]]` will actually just produce the exception itself. Naturally, once any one of the constituent `IO`s has failed, there is no point in continuing to evaluate the other nineteen, and so their fibers are all immediately `cancel`ed.

In these kinds of trivial examples involving primitive arithmetic, this kind of auto-cancelation doesn't represent much of a savings. However, if we were actually `parTraverse`ing a long `List` of `URL`s, where each one was being fetched in parallel, then perhaps failing fast and `cancel`ing all other actions on the first error would result in a significant savings in bandwidth and CPU.

Critically, all of this functionality is built on `Spawn` and nothing else, and so we effectively get it for free whenever this instance is available for a given `F`.

### Joining

Not all parallel operations are strictly "fire-and-forget". In fact, *most* of them aren't. Usually you want to fork off a few fibers to perform some task, then wait for them to finish, accept their results, and move forward. The Java `Thread` abstraction has the seldom-used `join` to attempt to encode this idea, and `Fiber` has something similar:

```scala mdoc
// don't use this in production; it is a simplified example
def both[F[_]: Spawn, A, B](fa: F[A], fb: F[B]): F[(A, B)] =
  for {
    fiberA <- fa.start
    fiberB <- fb.start

    a <- fiberA.joinWithNever
    b <- fiberB.joinWithNever
  } yield (a, b)
```

The `joinWithNever` function is a convenience method built on top of `join`, which is much more general. Specifically, the `Fiber#join` method returns `F[Outcome[F, E, A]]` (where `E` is the error type for `F`). This is a much more complex signature, but it gives us a lot of power when we need it.

`Outcome` has the following shape:

- `Succeeded` (containing a value of type `F[A]`)
- `Errored` (containing a value of type `E`, usually `Throwable`)
- `Canceled` (which contains nothing)

These represent the three possible termination states for a fiber, and by producing them within `join`, Cats Effect gives you the ability to react to each differently. For example, if the fiber in question produces an error, you may wish to wrap that error in some value and propagate it along within your own fiber:

```scala
fiber.join flatMap {
  case Outcome.Succeeded(fa) =>
    fa

  case Outcome.Errored(e) => 
    MyWrapper(e).pure[F]

  case Outcome.Canceled() => ???
}
```

Of course, that `Canceled()` case is exceptionally tricky. This case arises when the `fiber` you `join`ed was actually `cancel`ed, and so it never had a chance to raise an error *or* produce a result. In this outcome, you need to decide what to do. One option, for example, might be to raise an error, such as `new FiberCanceledException` or similar:

```scala
  case Outcome.Canceled() => 
    MonadThrow[F].raiseError(new FiberCanceledException)
```

That's a bit weird, but if you really don't expect the fiber to get canceled, perhaps it might fit your use-case. Another possibility might be that you want to cancel *yourself* in the event that the child fiber was canceled:

```scala
  case Outcome.Canceled() => 
    MonadCancel[F].canceled
```

There's a subtle issue here though: `canceled` produces an effect of type `F[Unit]`, specifically because we *might* be wrapped in an `uncancelable`, in which case we *can't* self-cancel. This is a problem when you view the whole context:

```scala
fiber.join flatMap {
  case Outcome.Succeeded(fa) => // => F[A]
    fa

  case Outcome.Errored(e) => // => F[A]
    MonadError[F, E].raiseError(e) 

  case Outcome.Canceled() => // => F[Unit]
    MonadCancel[F].canceled
}
```

The problem of course is the fact that the `Canceled()` branch returns the wrong type. We need an `A`, but it can only give us `Unit` because we don't actually know whether or not we're allowed to self-cancel (for comparison, `raiseError` always works and cannot be "disabled", so it doesn't have this problem). There are a couple ways to solve this. One option would be to have a default value for `A` which we just produce in the event that we aren't allowed to cancel:

```scala
case Outcome.Canceled() => 
  MonadCancel[F].canceled.as(default)
```

This probably works, but it's kind of hacky, and not all `A`s have sane defaults. However, we *could* use `Option`, which (by definition) always has a sane default:

```scala
import cats.conversions.all._

fiber.join flatMap {
  case Outcome.Succeeded(fa) => // => F[Some[A]]
    fa.map(Some(_))

  case Outcome.Errored(e) => // => F[Option[A]]
    MonadError[F, E].raiseError(e) 

  case Outcome.Canceled() => // => F[None]
    MonadCancel[F].canceled.as(None)
}
```

This works quite well, but now your downstream logic (anything *using* the results of this `join`) must explicitly distinguish between whether or not your child fiber was canceled *and* you weren't able to self-cancel. This may be what you want! Or it may not be.

If you are *really* sure that you're `join`ing and you're never, ever going to be wrapped in an `uncancelable`, you can use `never` to resolve this problem:

```scala
fiber.join flatMap {
  case Outcome.Succeeded(fa) => // => F[A]
    fa

  case Outcome.Errored(e) => // => F[A]
    MonadError[F, E].raiseError(e) 

  case Outcome.Canceled() => // => F[A]
    MonadCancel[F].canceled >> Spawn[F].never[A]
}
```

In English, the semantics of this are as follows:

- If the child fiber completed successfully, produce its result
- If it errored, re-raise the error within the current fiber
- If it canceled, attempt to self-cancel, and if the self-cancelation fails, **deadlock**

Sometimes this is an appropriate semantic, and the cautiously-verbose `joinWithNever` function implements it for you. It is worth noting that this semantic was the *default* in Cats Effect 2 (and in fact, no other semantic was possible).

Regardless of all of the above, `join` and `Outcome` give you enough flexibility to choose the appropriate response, regardless of your use-case.
