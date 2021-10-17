---
id: random
title: Random
---

A purely-functional implementation of a source of random information.

```scala mdoc
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
```scala mdoc:reset
import cats.Functor
import cats.syntax.functor._
import cats.effect.std.Random

def dieRoll[F[_]: Functor: Random]: F[Int] =
  Random[F].betweenInt(0, 6).map(_ + 1) // `6` is excluded from the range
```

## Derivation

An instance of `cats.effect.std.Random` can be created by any data type
capable of synchronously suspending side effects (i.e. `Sync`). Furthermore,
`Random` instances for monad transformers can be automatically derived for any
data type for which an instance of `Random` can already be derived.
