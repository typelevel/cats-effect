/*
 * Copyright 2020 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats
package effect
package std

import cats._
import cats.syntax.all._
import cats.effect.kernel._
import scala.util.{Random => SRandom}

/**
 * Random is the ability to get random information, each time getting
 * a different result.
 *
 * Alumnus of the Davenverse.
 */
trait Random[F[_]] {

  /**
   * Returns the next pseudorandom, uniformly distributed double value between min (inclusive) and max (exclusive) from this random number generator's sequence.
   */
  def betweenDouble(minInclusive: Double, maxExclusive: Double): F[Double]

  /**
   * Returns the next pseudorandom, uniformly distributed float value between min (inclusive) and max (exclusive) from this random number generator's sequence.
   */
  def betweenFloat(minInclusive: Float, maxExclusive: Float): F[Float]

  /**
   * Returns a pseudorandom, uniformly distributed int value between min (inclusive) and the specified value max (exclusive),
   * drawn from this random number generator's sequence.
   */
  def betweenInt(minInclusive: Int, maxExclusive: Int): F[Int]

  /**
   * Returns a pseudorandom, uniformly distributed long value between min (inclusive) and the specified value max (exclusive),
   * drawn from this random number generator's sequence.
   */
  def betweenLong(minInclusive: Long, maxExclusive: Long): F[Long]

  /**
   * Returns a pseudorandomly chosen alphanumeric character, equally chosen from A-Z, a-z, and 0-9.
   */
  def nextAlphaNumeric: F[Char]

  /**
   * Returns the next pseudorandom, uniformly distributed boolean value from this random number generator's sequence.
   */
  def nextBoolean: F[Boolean]

  /**
   * Generates n random bytes and returns them in a new array.
   */
  def nextBytes(n: Int): F[Array[Byte]]

  /**
   * Returns the next pseudorandom, uniformly distributed double value between 0.0 and 1.0 from this random number generator's sequence.
   */
  def nextDouble: F[Double]

  /**
   * Returns the next pseudorandom, uniformly distributed float value between 0.0 and 1.0 from this random number generator's sequence.
   */
  def nextFloat: F[Float]

  /**
   * Returns the next pseudorandom, Gaussian ("normally") distributed double value with mean 0.0 and standard deviation 1.0 from this random number generator's sequence
   */
  def nextGaussian: F[Double]

  /**
   * Returns the next pseudorandom, uniformly distributed int value from this random number generator's sequence.
   */
  def nextInt: F[Int]

  /**
   * Returns a pseudorandom, uniformly distributed int value between 0 (inclusive)
   * and the specified value (exclusive), drawn from this random number generator's sequence.
   */
  def nextIntBounded(n: Int): F[Int]

  /**
   * Returns the next pseudorandom, uniformly distributed long value from this random number generator's sequence.
   */
  def nextLong: F[Long]

  /**
   * Returns a pseudorandom, uniformly distributed long value between 0 (inclusive)
   * and the specified value (exclusive), drawn from this random number generator's sequence.
   */
  def nextLongBounded(n: Long): F[Long]

  /**
   * Returns the next pseudorandom, uniformly distributed value from the ASCII range 33-126.
   */
  def nextPrintableChar: F[Char]

  /**
   * Returns a pseudorandomly generated String.
   */
  def nextString(length: Int): F[String]

  /**
   * Returns a new collection of the same type in a randomly chosen order.
   */
  def shuffleList[A](l: List[A]): F[List[A]]

  /**
   * Returns a new collection of the same type in a randomly chosen order.
   */
  def shuffleVector[A](v: Vector[A]): F[Vector[A]]

}

object Random {

  def apply[F[_]](implicit ev: Random[F]): Random[F] = ev

  /**
   * Creates a new random number generator
   */
  def scalaUtilRandom[F[_]: Sync]: F[Random[F]] =
    Sync[F].delay {
      val sRandom = new SRandom()
      new ScalaRandom[F](sRandom.pure[F]) {}
    }

  /**
   * Creates Several Random Number Generators and equally
   * allocates the load across those instances.
   *
   * From the java class docs:
   * https://docs.oracle.com/javase/8/docs/api/java/util/Random.html#java.util.Random
   *
   * Instances of java.util.Random are threadsafe.
   * However, the concurrent use of the same java.util.Random instance
   * across threads may encounter contention and consequent poor performance.
   * Consider instead using ThreadLocalRandom in multithreaded designs.
   */
  def scalaUtilRandomN[F[_]: Sync](n: Int): F[Random[F]] =
    for {
      ref <- Ref[F].of(0)
      array <- Sync[F].delay(Array.fill(n)(new SRandom()))
    } yield {
      def incrGet = ref.modify(i => (if (i < (n - 1)) i + 1 else 0, i))
      def selectRandom = incrGet.map(array(_))
      new ScalaRandom[F](selectRandom) {}
    }

  /**
   * Creates a new random number generator using a single integer seed.
   */
  def scalaUtilRandomSeedInt[F[_]: Sync](seed: Int): F[Random[F]] =
    Sync[F].delay {
      val sRandom = new SRandom(seed)
      new ScalaRandom[F](sRandom.pure[F]) {}
    }

  /**
   * Creates a new random number generator using a single long seed.
   */
  def scalaUtilRandomSeedLong[F[_]: Sync](seed: Long): F[Random[F]] =
    Sync[F].delay {
      val sRandom = new SRandom(seed)
      new ScalaRandom[F](sRandom.pure[F]) {}
    }

  /**
   * Lift Java Random to this algebra.
   * Note: this implies the ability for external locations to manipulate
   * the underlying state without referential transparency.
   */
  def javaUtilRandom[F[_]: Sync](random: java.util.Random): F[Random[F]] =
    Sync[F].delay {
      val sRandom = new SRandom(random)
      new ScalaRandom[F](sRandom.pure[F]) {}
    }

  def javaUtilConcurrentThreadLocalRandom[F[_]: Sync]: Random[F] =
    new ScalaRandom[F](
      Sync[F].delay(new SRandom(java.util.concurrent.ThreadLocalRandom.current()))) {}

  def javaSecuritySecureRandom[F[_]: Sync](n: Int): F[Random[F]] =
    for {
      ref <- Ref[F].of(0)
      array <- Sync[F].delay(Array.fill(n)(new SRandom(new java.security.SecureRandom)))
    } yield {
      def incrGet = ref.modify(i => (if (i < (n - 1)) i + 1 else 0, i))
      def selectRandom = incrGet.map(array(_))
      new ScalaRandom[F](selectRandom) {}
    }

  def javaSecuritySecureRandom[F[_]: Sync]: F[Random[F]] =
    Sync[F].delay(new java.security.SecureRandom).flatMap(r => javaUtilRandom(r))

  private abstract class ScalaRandom[F[_]: Sync](f: F[SRandom]) extends Random[F] {

    def betweenLong(minInclusive: Long, maxExclusive: Long): F[Long] = {
      require(minInclusive < maxExclusive, "Invalid bounds")
      val difference = maxExclusive - minInclusive
      for {
        out <-
          if (difference >= 0) {
            nextLongBounded(difference).map(_ + minInclusive)
          } else {
            /* The interval size here is greater than Long.MaxValue,
             * so the loop will exit with a probability of at least 1/2.
             */
            def loop(): F[Long] = {
              nextLong.flatMap { n =>
                if (n >= minInclusive && n < maxExclusive) n.pure[F]
                else loop()
              }
            }
            loop()
          }
      } yield out
    }

    def betweenInt(minInclusive: Int, maxExclusive: Int): F[Int] = {
      require(minInclusive < maxExclusive, "Invalid bounds")
      val difference = maxExclusive - minInclusive
      for {
        out <-
          if (difference >= 0) {
            nextIntBounded(difference).map(_ + minInclusive)
          } else {
            /* The interval size here is greater than Int.MaxValue,
             * so the loop will exit with a probability of at least 1/2.
             */
            def loop(): F[Int] = {
              nextInt.flatMap { n =>
                if (n >= minInclusive && n < maxExclusive) n.pure[F]
                else loop()
              }
            }
            loop()
          }
      } yield out
    }

    def betweenFloat(minInclusive: Float, maxExclusive: Float): F[Float] = {
      require(minInclusive < maxExclusive, "Invalid bounds")
      for {
        f <- nextFloat
      } yield {
        val next = f * (maxExclusive - minInclusive) + minInclusive
        if (next < maxExclusive) next
        else Math.nextAfter(maxExclusive, Float.NegativeInfinity)
      }
    }

    def betweenDouble(minInclusive: Double, maxExclusive: Double): F[Double] = {
      require(minInclusive < maxExclusive, "Invalid bounds")
      for {
        d <- nextDouble
      } yield {
        val next = d * (maxExclusive - minInclusive) + minInclusive
        if (next < maxExclusive) next
        else Math.nextAfter(maxExclusive, Double.NegativeInfinity)
      }
    }

    def nextAlphaNumeric: F[Char] = {
      val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
      nextIntBounded(chars.length()).map(chars.charAt(_))
    }

    def nextBoolean: F[Boolean] =
      for {
        r <- f
        out <- Sync[F].delay(r.nextBoolean())
      } yield out

    def nextBytes(n: Int): F[Array[Byte]] =
      for {
        r <- f
        bytes = new Array[Byte](0 max n)
        _ <- Sync[F].delay(r.nextBytes(bytes))
      } yield bytes

    def nextDouble: F[Double] =
      for {
        r <- f
        out <- Sync[F].delay(r.nextDouble())
      } yield out

    def nextFloat: F[Float] =
      for {
        r <- f
        out <- Sync[F].delay(r.nextFloat())
      } yield out

    def nextGaussian: F[Double] =
      for {
        r <- f
        out <- Sync[F].delay(r.nextGaussian())
      } yield out

    def nextInt: F[Int] =
      for {
        r <- f
        out <- Sync[F].delay(r.nextInt())
      } yield out

    def nextIntBounded(n: Int): F[Int] =
      for {
        r <- f
        out <- Sync[F].delay(r.self.nextInt(n))
      } yield out

    def nextLong: F[Long] =
      for {
        r <- f
        out <- Sync[F].delay(r.nextLong())
      } yield out

    def nextLongBounded(n: Long): F[Long] = {
      require(n > 0, "n must be positive")

      /*
       * Divide n by two until small enough for nextInt. On each
       * iteration (at most 31 of them but usually much less),
       * randomly choose both whether to include high bit in result
       * (offset) and whether to continue with the lower vs upper
       * half (which makes a difference only if odd).
       */
      for {
        offset <- Ref[F].of(0L)
        _n <- Ref[F].of(n)
        _ <- Monad[F].whileM_(_n.get.map(_ >= Integer.MAX_VALUE))(
          for {
            bits <- nextIntBounded(2)
            halfn <- _n.get.map(_ >>> 1)
            nextN <- if ((bits & 2) == 0) halfn.pure[F] else _n.get.map(_ - halfn)
            _ <-
              if ((bits & 1) == 0) _n.get.flatMap(n => offset.update(_ + (n - nextN)))
              else Applicative[F].unit
            _ <- _n.set(nextN)
          } yield ()
        )
        finalOffset <- offset.get
        int <- _n.get.flatMap(l => nextIntBounded(l.toInt))
      } yield finalOffset + int
    }

    def nextPrintableChar: F[Char] =
      for {
        r <- f
        out <- Sync[F].delay(r.nextPrintableChar())
      } yield out

    def nextString(length: Int): F[String] =
      for {
        r <- f
        out <- Sync[F].delay(r.nextString(length))
      } yield out

    def shuffleList[A](l: List[A]): F[List[A]] =
      for {
        r <- f
        out <- Sync[F].delay(r.shuffle(l))
      } yield out

    def shuffleVector[A](v: Vector[A]): F[Vector[A]] =
      for {
        r <- f
        out <- Sync[F].delay(r.shuffle(v))
      } yield out
  }
}
