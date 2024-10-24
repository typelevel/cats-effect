/*
 * Copyright 2020-2024 Typelevel
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
import cats.data.{
  EitherT,
  IndexedReaderWriterStateT,
  IndexedStateT,
  IorT,
  Kleisli,
  OptionT,
  WriterT
}
import cats.effect.kernel._
import cats.syntax.all._

import scala.annotation.tailrec
import scala.util.{Random => SRandom}

/**
 * Random is the ability to get random information, each time getting a different result.
 *
 * Alumnus of the Davenverse.
 */
trait Random[F[_]] { self =>

  /**
   * Returns the next pseudorandom, uniformly distributed double value between min (inclusive)
   * and max (exclusive) from this random number generator's sequence.
   */
  def betweenDouble(minInclusive: Double, maxExclusive: Double): F[Double]

  /**
   * Returns the next pseudorandom, uniformly distributed float value between min (inclusive)
   * and max (exclusive) from this random number generator's sequence.
   */
  def betweenFloat(minInclusive: Float, maxExclusive: Float): F[Float]

  /**
   * Returns a pseudorandom, uniformly distributed int value between min (inclusive) and the
   * specified value max (exclusive), drawn from this random number generator's sequence.
   */
  def betweenInt(minInclusive: Int, maxExclusive: Int): F[Int]

  /**
   * Returns a pseudorandom, uniformly distributed long value between min (inclusive) and the
   * specified value max (exclusive), drawn from this random number generator's sequence.
   */
  def betweenLong(minInclusive: Long, maxExclusive: Long): F[Long]

  /**
   * Returns a pseudorandomly chosen alphanumeric character, equally chosen from A-Z, a-z, and
   * 0-9.
   */
  def nextAlphaNumeric: F[Char]

  /**
   * Returns the next pseudorandom, uniformly distributed boolean value from this random number
   * generator's sequence.
   */
  def nextBoolean: F[Boolean]

  /**
   * Generates n random bytes and returns them in a new array.
   */
  def nextBytes(n: Int): F[Array[Byte]]

  /**
   * Returns the next pseudorandom, uniformly distributed double value between 0.0 and 1.0 from
   * this random number generator's sequence.
   */
  def nextDouble: F[Double]

  /**
   * Returns the next pseudorandom, uniformly distributed float value between 0.0 and 1.0 from
   * this random number generator's sequence.
   */
  def nextFloat: F[Float]

  /**
   * Returns the next pseudorandom, Gaussian ("normally") distributed double value with mean 0.0
   * and standard deviation 1.0 from this random number generator's sequence
   */
  def nextGaussian: F[Double]

  /**
   * Returns the next pseudorandom, uniformly distributed int value from this random number
   * generator's sequence.
   */
  def nextInt: F[Int]

  /**
   * Returns a pseudorandom, uniformly distributed int value between 0 (inclusive) and the
   * specified value (exclusive), drawn from this random number generator's sequence.
   */
  def nextIntBounded(n: Int): F[Int]

  /**
   * Returns the next pseudorandom, uniformly distributed long value from this random number
   * generator's sequence.
   */
  def nextLong: F[Long]

  /**
   * Returns a pseudorandom, uniformly distributed long value between 0 (inclusive) and the
   * specified value (exclusive), drawn from this random number generator's sequence.
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

  /**
   * Pseudorandomly chooses one of the given values.
   */
  def oneOf[A](x: A, xs: A*)(implicit ev: Applicative[F]): F[A] =
    if (xs.isEmpty) {
      x.pure[F]
    } else {
      nextIntBounded(1 + xs.size).map {
        case 0 => x
        case i => xs(i - 1)
      }
    }

  /**
   * Pseudorandomly chooses an element of the given collection.
   *
   * @return
   *   a failed effect (NoSuchElementException) if the given collection is empty
   */
  def elementOf[A](xs: Iterable[A])(implicit ev: MonadThrow[F]): F[A] = {
    val requireNonEmpty: F[Unit] =
      if (xs.nonEmpty) ().pure[F]
      else
        new NoSuchElementException("Cannot choose a random element of an empty collection")
          .raiseError[F, Unit]

    requireNonEmpty *> nextIntBounded(xs.size).map { i =>
      xs match {
        case seq: scala.collection.Seq[A] => seq(i)
        case _ =>
          // we don't have an apply method, so iterate through
          // the collection's iterator until we reach the chosen index
          @tailrec
          def loop(it: Iterator[A], n: Int): A = {
            val next = it.next()
            if (n == i) next
            else loop(it, n + 1)
          }
          loop(xs.iterator, 0)
      }
    }
  }

  /**
   * Modifies the context in which this [[Random]] operates using the natural transformation
   * `f`.
   *
   * @return
   *   a [[Random]] in the new context obtained by mapping the current one using `f`
   */
  def mapK[G[_]](f: F ~> G): Random[G] =
    new Random.TranslatedRandom[F, G](self)(f) {}

}

object Random extends RandomCompanionPlatform {

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
   * [[Random]] instance built for `cats.data.EitherT` values initialized with any `F` data type
   * that also implements `Random`.
   */
  implicit def catsEitherTRandom[F[_]: Random: Functor, L]: Random[EitherT[F, L, *]] =
    Random[F].mapK(EitherT.liftK)

  /**
   * [[Random]] instance built for `cats.data.Kleisli` values initialized with any `F` data type
   * that also implements `Random`.
   */
  implicit def catsKleisliRandom[F[_]: Random, R]: Random[Kleisli[F, R, *]] =
    Random[F].mapK(Kleisli.liftK)

  /**
   * [[Random]] instance built for `cats.data.OptionT` values initialized with any `F` data type
   * that also implements `Random`.
   */
  implicit def catsOptionTRandom[F[_]: Random: Functor]: Random[OptionT[F, *]] =
    Random[F].mapK(OptionT.liftK)

  /**
   * [[Random]] instance built for `cats.data.IndexedStateT` values initialized with any `F`
   * data type that also implements `Random`.
   */
  implicit def catsIndexedStateTRandom[F[_]: Random: Applicative, S]
      : Random[IndexedStateT[F, S, S, *]] =
    Random[F].mapK(IndexedStateT.liftK)

  /**
   * [[Random]] instance built for `cats.data.WriterT` values initialized with any `F` data type
   * that also implements `Random`.
   */
  implicit def catsWriterTRandom[
      F[_]: Random: Applicative,
      L: Monoid
  ]: Random[WriterT[F, L, *]] =
    Random[F].mapK(WriterT.liftK)

  /**
   * [[Random]] instance built for `cats.data.IorT` values initialized with any `F` data type
   * that also implements `Random`.
   */
  implicit def catsIorTRandom[F[_]: Random: Functor, L]: Random[IorT[F, L, *]] =
    Random[F].mapK(IorT.liftK)

  /**
   * [[Random]] instance built for `cats.data.IndexedReaderWriterStateT` values initialized with
   * any `F` data type that also implements `Random`.
   */
  implicit def catsIndexedReaderWriterStateTRandom[
      F[_]: Random: Applicative,
      E,
      L: Monoid,
      S
  ]: Random[IndexedReaderWriterStateT[F, E, L, S, S, *]] =
    Random[F].mapK(IndexedReaderWriterStateT.liftK)

  /**
   * Creates Several Random Number Generators and equally allocates the load across those
   * instances.
   *
   * From the java class docs:
   * https://docs.oracle.com/javase/8/docs/api/java/util/Random.html#java.util.Random
   *
   * Instances of java.util.Random are threadsafe. However, the concurrent use of the same
   * java.util.Random instance across threads may encounter contention and consequent poor
   * performance. Consider instead using ThreadLocalRandom in multithreaded designs.
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
   * Lift Java Random to this algebra. Note: this implies the ability for external locations to
   * manipulate the underlying state without referential transparency.
   */
  def javaUtilRandom[F[_]: Sync](random: java.util.Random): F[Random[F]] =
    Sync[F].delay {
      val sRandom = new SRandom(random)
      new ScalaRandom[F](sRandom.pure[F]) {}
    }

  def javaUtilConcurrentThreadLocalRandom[F[_]: Sync]: Random[F] =
    new ThreadLocalRandom[F] {}

  @deprecated("Call SecureRandom.javaSecuritySecureRandom", "3.4.0")
  def javaSecuritySecureRandom[F[_]: Sync](n: Int): F[Random[F]] =
    SecureRandom.javaSecuritySecureRandom[F](n).widen[Random[F]]

  @deprecated("Call SecureRandom.javaSecuritySecureRandom", "3.4.0")
  def javaSecuritySecureRandom[F[_]: Sync]: F[Random[F]] =
    SecureRandom.javaSecuritySecureRandom[F].widen[Random[F]]

  private[std] sealed abstract class RandomCommon[F[_]: Sync] extends Random[F] {
    def betweenDouble(minInclusive: Double, maxExclusive: Double): F[Double] =
      for {
        _ <- require(minInclusive < maxExclusive, "Invalid bounds")
        d <- nextDouble
      } yield {
        val next = d * (maxExclusive - minInclusive) + minInclusive
        if (next < maxExclusive) next
        else Math.nextAfter(maxExclusive, Double.NegativeInfinity)
      }

    def betweenFloat(minInclusive: Float, maxExclusive: Float): F[Float] =
      for {
        _ <- require(minInclusive < maxExclusive, "Invalid bounds")
        f <- nextFloat
      } yield {
        val next = f * (maxExclusive - minInclusive) + minInclusive
        if (next < maxExclusive) next
        else Math.nextAfter(maxExclusive, Float.NegativeInfinity)
      }

    def betweenInt(minInclusive: Int, maxExclusive: Int): F[Int] =
      require(minInclusive < maxExclusive, "Invalid bounds") *> {
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

    def betweenLong(minInclusive: Long, maxExclusive: Long): F[Long] =
      require(minInclusive < maxExclusive, "Invalid bounds") *> {
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

    def nextAlphaNumeric: F[Char] = {
      val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
      nextIntBounded(chars.length()).map(chars.charAt(_))
    }

    def nextLongBounded(n: Long): F[Long] = {
      /*
       * Divide n by two until small enough for nextInt. On each
       * iteration (at most 31 of them but usually much less),
       * randomly choose both whether to include high bit in result
       * (offset) and whether to continue with the lower vs upper
       * half (which makes a difference only if odd).
       */
      for {
        _ <- require(n > 0, s"n must be positive, but was $n")
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

    private def require(condition: Boolean, errorMessage: => String): F[Unit] =
      if (condition) ().pure[F]
      else new IllegalArgumentException(errorMessage).raiseError[F, Unit]

  }

  private[std] abstract class ScalaRandom[F[_]: Sync](f: F[SRandom], hint: Sync.Type)
      extends RandomCommon[F] {

    def this(f: F[SRandom]) = this(f, Sync.Type.Delay)

    def nextBoolean: F[Boolean] =
      for {
        r <- f
        out <- Sync[F].suspend(hint)(r.nextBoolean())
      } yield out

    def nextBytes(n: Int): F[Array[Byte]] =
      for {
        r <- f
        out <- Sync[F].suspend(hint) {
          val bytes = new Array[Byte](0 max n)
          r.nextBytes(bytes)
          bytes
        }
      } yield out

    def nextDouble: F[Double] =
      for {
        r <- f
        out <- Sync[F].suspend(hint)(r.nextDouble())
      } yield out

    def nextFloat: F[Float] =
      for {
        r <- f
        out <- Sync[F].suspend(hint)(r.nextFloat())
      } yield out

    def nextGaussian: F[Double] =
      for {
        r <- f
        out <- Sync[F].suspend(hint)(r.nextGaussian())
      } yield out

    def nextInt: F[Int] =
      for {
        r <- f
        out <- Sync[F].suspend(hint)(r.nextInt())
      } yield out

    def nextIntBounded(n: Int): F[Int] =
      for {
        r <- f
        out <- Sync[F].suspend(hint)(r.self.nextInt(n))
      } yield out

    def nextLong: F[Long] =
      for {
        r <- f
        out <- Sync[F].suspend(hint)(r.nextLong())
      } yield out

    def nextPrintableChar: F[Char] =
      for {
        r <- f
        out <- Sync[F].suspend(hint)(r.nextPrintableChar())
      } yield out

    def nextString(length: Int): F[String] =
      for {
        r <- f
        out <- Sync[F].suspend(hint)(r.nextString(length))
      } yield out

    def shuffleList[A](l: List[A]): F[List[A]] =
      for {
        r <- f
        out <- Sync[F].suspend(hint)(r.shuffle(l))
      } yield out

    def shuffleVector[A](v: Vector[A]): F[Vector[A]] =
      for {
        r <- f
        out <- Sync[F].suspend(hint)(r.shuffle(v))
      } yield out
  }

  private abstract class ThreadLocalRandom[F[_]: Sync] extends RandomCommon[F] {
    def nextBoolean: F[Boolean] =
      Sync[F].delay(localRandom().nextBoolean())

    def nextBytes(n: Int): F[Array[Byte]] = Sync[F].delay {
      val bytes = new Array[Byte](0 max n)
      localRandom().nextBytes(bytes)
      bytes
    }

    def nextDouble: F[Double] =
      Sync[F].delay(localRandom().nextDouble())

    def nextFloat: F[Float] =
      Sync[F].delay(localRandom().nextFloat())

    def nextGaussian: F[Double] =
      Sync[F].delay(localRandom().nextGaussian())

    def nextInt: F[Int] =
      Sync[F].delay(localRandom().nextInt())

    def nextIntBounded(n: Int): F[Int] =
      Sync[F].delay(localRandom().self.nextInt(n))

    def nextLong: F[Long] =
      Sync[F].delay(localRandom().nextLong())

    def nextPrintableChar: F[Char] =
      Sync[F].delay(localRandom().nextPrintableChar())

    def nextString(length: Int): F[String] =
      Sync[F].delay(localRandom().nextString(length))

    def shuffleList[A](l: List[A]): F[List[A]] =
      Sync[F].delay(localRandom().shuffle(l))

    def shuffleVector[A](v: Vector[A]): F[Vector[A]] =
      Sync[F].delay(localRandom().shuffle(v))
  }

  private[this] def localRandom() = new SRandom(
    java.util.concurrent.ThreadLocalRandom.current())

  private[std] abstract class TranslatedRandom[F[_], G[_]](self: Random[F])(f: F ~> G)
      extends Random[G] {
    override def betweenDouble(minInclusive: Double, maxExclusive: Double): G[Double] =
      f(self.betweenDouble(minInclusive, maxExclusive))

    override def betweenFloat(minInclusive: Float, maxExclusive: Float): G[Float] =
      f(self.betweenFloat(minInclusive, maxExclusive))

    override def betweenInt(minInclusive: Int, maxExclusive: Int): G[Int] =
      f(self.betweenInt(minInclusive, maxExclusive))

    override def betweenLong(minInclusive: Long, maxExclusive: Long): G[Long] =
      f(self.betweenLong(minInclusive, maxExclusive))

    override def nextAlphaNumeric: G[Char] =
      f(self.nextAlphaNumeric)

    override def nextBoolean: G[Boolean] =
      f(self.nextBoolean)

    override def nextBytes(n: Int): G[Array[Byte]] =
      f(self.nextBytes(n))

    override def nextDouble: G[Double] =
      f(self.nextDouble)

    override def nextFloat: G[Float] =
      f(self.nextFloat)

    override def nextGaussian: G[Double] =
      f(self.nextGaussian)

    override def nextInt: G[Int] =
      f(self.nextInt)

    override def nextIntBounded(n: Int): G[Int] =
      f(self.nextIntBounded(n))

    override def nextLong: G[Long] =
      f(self.nextLong)

    override def nextLongBounded(n: Long): G[Long] =
      f(self.nextLongBounded(n))

    override def nextPrintableChar: G[Char] =
      f(self.nextPrintableChar)

    override def nextString(length: Int): G[String] =
      f(self.nextString(length))

    override def shuffleList[A](l: List[A]): G[List[A]] =
      f(self.shuffleList(l))

    override def shuffleVector[A](v: Vector[A]): G[Vector[A]] =
      f(self.shuffleVector(v))

  }
}

// Vestigial shim
private[std] trait RandomCompanionPlatform
