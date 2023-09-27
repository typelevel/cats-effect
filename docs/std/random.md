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

## Creating and using a `Random` instance

The following example shows the usage of `Random` in a way that is *referentially transparent*, meaning that you can know from the 'outside' what the result will be by knowing the inputs:
```scala mdoc:silent
import cats.effect.std.Random
import cats.implicits.*
import cats.Monad
import cats.effect.unsafe.implicits.global

object BusinessLogic {

  // use the standard implementation of Random backed by java.util.Random()
  // (the same implementation as Random.javaUtilRandom(43))
  val randomizer: IO[Random[IO]] = Random.scalaUtilRandom[IO]

  // other possible implementations you could choose
  val sr = SecureRandom.javaSecuritySecureRandom(3) // backed java.security.SecureRandom()
  val jr = Random.javaUtilRandom(new java.util.Random()) // pass in the backing randomizer

  // calling .unsafeRunSync() in business logic is an anti-patten. 
  // Doing it here to make the example easy to follow.
  def unsafeGetMessage: String =
    Magic
      .getMagicNumber[IO](5, randomizer)
      .unsafeRunSync()
}

object Magic {
  def getMagicNumber[F[_] : Monad](
    mult: Int,
    randomizer: F[Random[F]]
  ): F[String] =
    for {
      rand <- randomizer.flatMap(random => random.betweenInt(1, 11)) // 11 is excluded
      number = rand * mult
      msg = s"the magic number is: $number"
    } yield msg
}
```

Since `getMagicNumber` is not dependent on a particular implementation (it's referentially transparent), you can give it another instance of the type class as you see fit.

This is particularly useful when testing. In the following example, we need our `Random` implementation to give back a stable value so we can ensure everything else works correctly, and our test assert succeeds. Since `randomizer` is passed into `getMagicNumber`, we can swap it out in our test with a `Random` of which we can make stable. In our test implementation, calls to `betweenInt` will *always* give back `7`. This stability of "randomness" allows us to test that our function `getMagicNumber` does what we intend:

```scala mdoc:silent
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.must.Matchers.*

class MagicSpec extends AnyFunSuite {

  // for testing, create a Random instance that gives back the same number every time. With
  // this version of the Random type class, we can test our business logic works as intended.
  implicit val r: IO[Random[IO]] = IO.pure(
    new Random[IO] {
      def betweenInt(minInclusive: Int, maxExclusive: Int): IO[Int] =
        IO.pure(7) // gives back 7 every call

      // all other methods not implemented since they won't be called in our test
      def betweenDouble(minInclusive: Double, maxExclusive: Double): IO[Double] = ???
      def betweenFloat(minInclusive: Float, maxExclusive: Float): IO[Float] = ???
      // ... snip: cutting out other method implementations for brevity
    }
  )
    
  test("getMagicNumber text matches expectations") {
    val result = MagicSpec.getMagicNumber[IO](5)
    result.mustBe("the magic number is: 35")
  }
}
```

## Derivation

An instance of `cats.effect.std.Random` can be created by any data type
capable of synchronously suspending side effects (i.e. `Sync`). Furthermore,
`Random` instances for monad transformers can be automatically derived for any
data type for which an instance of `Random` can already be derived.
