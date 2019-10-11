---
layout: docsplus
title:  "LiftIO"
number: 4
source: "shared/src/main/scala/cats/effect/LiftIO.scala"
scaladoc: "#cats.effect.LiftIO"
---

A `Monad` that can convert any given `IO[A]` into a `F[A]`, useful for defining parametric signatures and composing monad transformer stacks.

```scala mdoc:silent
import cats.effect.IO

trait LiftIO[F[_]] {
  def liftIO[A](ioa: IO[A]): F[A]
}
```

Let's say your effect stack in your app is the following:

```scala mdoc:reset:silent
import cats.effect.{LiftIO, IO}
import scala.concurrent.Future

type MyEffect[A] = Future[Either[Throwable, A]]

implicit def myEffectLiftIO: LiftIO[MyEffect] =
  new LiftIO[MyEffect] {
    override def liftIO[A](ioa: IO[A]): MyEffect[A] = {
      ioa.attempt.unsafeToFuture()
    }
  }
```

Having an implicit instance of `LiftIO` in scope you can, at any time, `lift` any given `IO[A]` into `MyEffect[A]`:

```scala mdoc:silent
val ioa: IO[String] = IO("Hello World!")
val effect: MyEffect[String] = LiftIO[MyEffect].liftIO(ioa)
```

A more interesting example:

```scala mdoc:silent
import cats.data.EitherT
import cats.implicits._

import scala.concurrent.ExecutionContext.Implicits.global

val L = implicitly[LiftIO[MyEffect]]

val service1: MyEffect[Int] = Future.successful(Right(22))
val service2: MyEffect[Boolean] = Future.successful(Right(true))
val service3: MyEffect[String] = Future.successful(Left(new Exception("boom!")))

val program: MyEffect[String] =
  (for {
    _ <- EitherT(service1)
    x <- EitherT(service2)
    y <- EitherT(if (x) L.liftIO(IO("from io")) else service3)
  } yield y).value
```
