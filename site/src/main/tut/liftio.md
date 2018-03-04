---
layout: docs
title:  "LiftIO"
number: 2
source: "shared/src/main/scala/cats/effect/LiftIO.scala"
scaladoc: "#cats.effect.LiftIO"
---

# LiftIO

A `Monad` that can convert any given `IO[A]` into a `F[A]`, useful for defining parametric signatures and composing monad transformer stacks.

```scala
import cats.effect.IO

trait LiftIO[F[_]] {
  def liftIO[A](ioa: IO[A]): F[A]
}
```

Let's say your effect stack in your app is the following:

```scala
import cats.effect.IO
import monix.eval.Task

type MyEffect[A] = Task[Either[Throwable, A]]

implicit def myEffectLiftIO: LiftIO[MyEffect] =
  new LiftIO[MyEffect] {
    override def liftIO[A](ioa: IO[A]): MyEffect[A] = {
      ioa.attempt.to[Task]
    }
  }
```

Having an implicit instance of `LiftIO` in scope you can, at any time, `lift` any given `IO[A]` into `MyEffect[A]`:

```scala
import cats.effect.IO

val ioa: IO[String] = IO("Hello World!")

val effect: MyEffect[String] = LiftIO[MyEffect].liftIO(ioa)
```

A more interesting example:

```scala
import cats.effect.IO

val L = implicitly[LiftIO[MyEffect]]

val service1: MyEffect[Int] = ???
val service2: MyEffect[Boolean] = ???
val service3: MyEffect[String] = ???

val program: MyEffect[String] =
  for {
    n <- service1
    x <- service2
    y <- if (x) L.liftIO(IO("from io")) else service3
  } yield x
```
