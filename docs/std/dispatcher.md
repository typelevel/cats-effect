---
id: dispatcher
title: Dispatcher
---

![](assets/dispatcher.jpeg)

`Dispatcher` is a fiber-based [`Supervisor`](./supervisor.md) utility for evaluating effects across an impure boundary. This is useful when working with reactive interfaces that produce potentially many values (as opposed to one), and for each value, some effect in `F` must be performed (like inserting each value into a queue).

An instance of `Dispatcher` can be derived for any effect type conforming to the [`Async`](../typeclasses/async.md) typeclass.

Let's say we are integrating with an interface that looks like this:

```scala
abstract class ImpureInterface {
  def onMessage(msg: String): Unit

  def init(): Unit = {
    onMessage("init")
  }

  // ...
}
```

As a user we're supposed to create an implementation of this class where we implement `onMessage`.

Let's say that we have a [`queue`](./queue.md) and that we want to add each message received to the queue. We might,
naively, try to do the following

```scala
for {
    queue <- Queue.unbounded[IO, String]
    impureInterface = new ImpureInterface {
      override def onMessage(msg: String): Unit = queue.offer(msg) // This returns an IO, so nothing really happens!
    }
    _ <- IO.delay(impureInterface.init())
    value <- queue.tryTake
  } yield value match {
    case Some(v) => println(s"Value found in queue! $v")
    case None    => println("Value not found in queue :(")
  }
```

This will print "Value not found in queue :(", since our implementation of `onMessage` 
doesn't really *do* anything. It just creates an `IO` value which is just a description of an effect,
without actually executing it. (Here the `scalac` option `-Ywarn-value-discard` might help hint at this problem.)

It is in these cases that `Dispatcher` comes in handy. Here's how it could be used:

```scala
Dispatcher[IO].use { dispatcher =>
  for {
    queue <- Queue.unbounded[IO, String]
    impureInterface <-
      IO.delay {
        new ImpureInterface {
          override def onMessage(msg: String): Unit =
            dispatcher.unsafeRunSync(queue.offer(msg))
        }
      }
    _ <- IO.delay(impureInterface.init())
    value <- queue.tryTake
  } yield value match {
    case Some(v) => println(s"Value found in queue! $v")
    case None    => println("Value not found in queue :(")
  }
}
```

It prints "Value found in queue! init"!

The example above calls `unsafeRunSync` on the dispatcher, but more functions are exposed:

```scala

trait Dispatcher[F[_]] extends DispatcherPlatform[F] {

  def unsafeToFutureCancelable[A](fa: F[A]): (Future[A], () => Future[Unit])

  def unsafeToFuture[A](fa: F[A]): Future[A] =
    unsafeToFutureCancelable(fa)._1

  def unsafeRunCancelable[A](fa: F[A]): () => Future[Unit] =
    unsafeToFutureCancelable(fa)._2

  def unsafeRunAndForget[A](fa: F[A]): Unit = {
    unsafeToFutureCancelable(fa)
    ()
  }

  //Only on the JVM
  def unsafeRunSync[A](fa: F[A]): A
}
```

Creating an instance of `Dispatcher` is very cheap - you are encouraged to instantiate it 
where necessary rather than wiring a single instance throughout an application.


## Cats Effect 2

Users of Cats Effect 2 may be familiar with the `Effect` and `ConcurrentEffect`
typeclasses. These have been removed as they constrained implementations of the
typeclasses too much by forcing them to be embeddable in `IO` via `def
toIO[A](fa: F[A]): IO[A]`. However, these typeclasses also had a valid use-case
for unsafe running of effects to interface with impure APIs (`Future`, `NIO`,
etc). `Dispatcher` now addresses this use-case.
