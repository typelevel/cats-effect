---
id: random
title: Random
---

A purely-functional implementation of a source of random information.

```scala
trait Random[F[_]] {
  def nextInt: F[Int]

  def shuffleList[A](list: List[A]): F[List[A]]

  // ... and more
}
```

## API

The API of `Random` is created to closely resemble the `scala.util.Random` API,
so it should be fairly approachable to people familiar with the
[standard library](https://www.scala-lang.org/api/2.13.6/scala/util/Random.html).

Users can expect to find all methods available on a Scala standard library
`Random` instance also available on instances of this type class (with some
minor renamings to avoid method overloading, e.g. `betweenLong`,
`betweenDouble`, etc).

## Customizing the source of randomness

`cats.effect.std.Random` can be backed by a variety of random sources available
on the JVM:
- `scala.util.Random` (`Random.scalaUtilRandom[F: Sync]`)
    - Individual instances can be customized with an initial seed, or load
      balanced between several `scala.util.Random` instances
- `java.util.Random`
- `java.security.SecureRandom`

## Creating a `Random` instance

Obtaining an instance of `Random` can be as simple as:
```scala mdoc:silent
import cats.effect.IO
import cats.effect.std.Random

Random.scalaUtilRandom[IO]
```

## Using `Random`
```scala mdoc
import cats.effect.std.{Console, Random, SecureRandom}
import cats.{Functor, FlatMap}
import cats.syntax.all.toFlatMapOps
import cats.syntax.all.toFunctorOps

def dieRoll[F[_] : Functor : Random]: F[Int] =
  Random[F].betweenInt(0, 6).map(_ + 1)

// Scala 2.x & 3.x
def showMagicNumber[F[_] : Console : FlatMap](id: String, rnd: F[Random[F]]): F[Unit] =
  rnd.flatMap(implicit rnd => dieRoll[F].flatMap(i => Console[F].println(s"$id: $i")))

/* Scala 2.x with better-monadic-for compiler plugin
def showMagicNumber_bmf[F[_] : Console : FlatMap](id: String, rnd: F[Random[F]]): F[Unit] =
  for {
    implicit0(it: Random[F]) <- rnd
    i <- dieRoll[F]
    _ <- Console[F].println(s"$id: $i")
  } yield ()
 */

/* Scala 3.x only
def showMagicNumber3[F[_] : Console : FlatMap](id: String, rnd: F[Random[F]]): F[Unit] = 
  for {
    given Random[F] <- rnd
    i               <- dieRoll[F]
    _               <- Console[F].println(s"$id: $i")
  } yield ()
 */

val app = showMagicNumber("rnd", Random.scalaUtilRandom[IO]) *>
  showMagicNumber[IO]("sec-rnd", SecureRandom.javaSecuritySecureRandom[IO])

// required for unsafeRunSync()
import cats.effect.unsafe.implicits.global

app.unsafeRunSync()
```
## Derivation

An instance of `cats.effect.std.Random` can be created by any data type
capable of synchronously suspending side effects (i.e. `Sync`). Furthermore,
`Random` instances for monad transformers can be automatically derived for any
data type for which an instance of `Random` can already be derived.
