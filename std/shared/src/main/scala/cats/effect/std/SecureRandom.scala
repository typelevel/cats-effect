/*
 * Copyright 2020-2023 Typelevel
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
import cats.effect.std.Random.{ScalaRandom, TranslatedRandom}
import cats.syntax.all._

import scala.util.{Random => SRandom, Try}

import java.security.{SecureRandom => JavaSecureRandom}

/**
 * SecureRandom is the ability to get cryptographically strong random information. It is an
 * extension of the Random interface, but is used where weaker implementations must be
 * precluded.
 */
trait SecureRandom[F[_]] extends Random[F] { self =>
  override def mapK[G[_]](f: F ~> G): SecureRandom[G] =
    new TranslatedRandom[F, G](self)(f) with SecureRandom[G]
}

object SecureRandom extends SecureRandomCompanionPlatform {

  def apply[F[_]](implicit ev: SecureRandom[F]): SecureRandom[F] = ev

  /**
   * [[SecureRandom]] instance built for `cats.data.EitherT` values initialized with any `F`
   * data type that also implements `SecureRandom`.
   */
  implicit def catsEitherTRandom[F[_]: SecureRandom: Functor, L]
      : SecureRandom[EitherT[F, L, *]] =
    SecureRandom[F].mapK(EitherT.liftK)

  /**
   * [[SecureRandom]] instance built for `cats.data.Kleisli` values initialized with any `F`
   * data type that also implements `SecureRandom`.
   */
  implicit def catsKleisliSecureRandom[F[_]: SecureRandom, R]: SecureRandom[Kleisli[F, R, *]] =
    SecureRandom[F].mapK(Kleisli.liftK)

  /**
   * [[SecureRandom]] instance built for `cats.data.OptionT` values initialized with any `F`
   * data type that also implements `SecureRandom`.
   */
  implicit def catsOptionTSecureRandom[F[_]: SecureRandom: Functor]
      : SecureRandom[OptionT[F, *]] =
    SecureRandom[F].mapK(OptionT.liftK)

  /**
   * [[SecureRandom]] instance built for `cats.data.IndexedStateT` values initialized with any
   * `F` data type that also implements `SecureRandom`.
   */
  implicit def catsIndexedStateTSecureRandom[F[_]: SecureRandom: Applicative, S]
      : SecureRandom[IndexedStateT[F, S, S, *]] =
    SecureRandom[F].mapK(IndexedStateT.liftK)

  /**
   * [[SecureRandom]] instance built for `cats.data.WriterT` values initialized with any `F`
   * data type that also implements `SecureRandom`.
   */
  implicit def catsWriterTSecureRandom[
      F[_]: SecureRandom: Applicative,
      L: Monoid
  ]: SecureRandom[WriterT[F, L, *]] =
    SecureRandom[F].mapK(WriterT.liftK)

  /**
   * [[SecureRandom]] instance built for `cats.data.IorT` values initialized with any `F` data
   * type that also implements `SecureRandom`.
   */
  implicit def catsIorTSecureRandom[F[_]: SecureRandom: Functor, L]
      : SecureRandom[IorT[F, L, *]] =
    SecureRandom[F].mapK(IorT.liftK)

  /**
   * [[SecureRandom]] instance built for `cats.data.IndexedReaderWriterStateT` values
   * initialized with any `F` data type that also implements `SecureRandom`.
   */
  implicit def catsIndexedReaderWriterStateTSecureRandom[
      F[_]: SecureRandom: Applicative,
      E,
      L: Monoid,
      S
  ]: SecureRandom[IndexedReaderWriterStateT[F, E, L, S, S, *]] =
    SecureRandom[F].mapK(IndexedReaderWriterStateT.liftK)

  /**
   * @see
   *   implementation notes at [[[javaSecuritySecureRandom[F[_]](implicit*]]].
   */
  def javaSecuritySecureRandom[F[_]: Sync](n: Int): F[SecureRandom[F]] =
    for {
      ref <- Ref[F].of(0)
      array <- Sync[F].delay(Array.fill(n)(new SRandom(new JavaSecureRandom())))
    } yield {
      def incrGet = ref.modify(i => (if (i < (n - 1)) i + 1 else 0, i))
      def selectRandom = incrGet.map(array(_))
      new ScalaRandom[F](selectRandom) with SecureRandom[F] {}
    }

  /**
   * Create a non-blocking Random instance. This is more efficient than [[javaSecurityRandom]],
   * but requires a more thoughtful instance of random
   *
   * @param random
   *   a non-blocking instance of java.util.Random. It is the caller's responsibility to assure
   *   that the instance is non-blocking. If the instance blocks during seeding, it is the
   *   caller's responsibility to seed it.
   */
  private def javaUtilRandomNonBlocking[F[_]: Sync](
      random: JavaSecureRandom): F[SecureRandom[F]] =
    Sync[F]
      .delay(random)
      .map(r => new ScalaRandom[F](Applicative[F].pure(r)) with SecureRandom[F] {})

  /**
   * Creates a blocking Random instance. All calls to nextBytes are shifted to the blocking
   * pool. This is safer, but less effecient, than [[javaUtilRandomNonBlocking]].
   *
   * @param random
   *   a potentially blocking instance of java.util.Random
   */
  private def javaUtilRandomBlocking[F[_]: Sync](random: JavaSecureRandom): F[SecureRandom[F]] =
    Sync[F]
      .blocking(random)
      .map(r => new ScalaRandom[F](Applicative[F].pure(r)) with SecureRandom[F] {})

  /**
   * Creates a SecureRandom instance. On most platforms, it will be non-blocking. If a
   * non-blocking instance can't be guaranteed, falls back to a blocking implementation.
   *
   * On the JVM, delegates to [[java.security.SecureRandom]].
   *
   * In browsers, delegates to the
   * [[https://developer.mozilla.org/en-US/docs/Web/API/Web_Crypto_API Web Crypto API]].
   *
   * In Node.js, delegates to the [[https://nodejs.org/api/crypto.html crypto module]].
   *
   * On Native, delegates to
   * [[https://man7.org/linux/man-pages/man3/getentropy.3.html getentropy]] which is supported
   * on Linux, macOS, and BSD. Unsupported platforms such as Windows will encounter link-time
   * errors.
   */
  def javaSecuritySecureRandom[F[_]: Sync]: F[SecureRandom[F]] = {
    // This is a known, non-blocking, threadsafe algorithm
    def happyRandom = Sync[F].delay(JavaSecureRandom.getInstance("NativePRNGNonBlocking"))

    def fallback = Sync[F].delay(new JavaSecureRandom())

    def isThreadsafe(rnd: JavaSecureRandom) =
      rnd
        .getProvider
        .getProperty("SecureRandom." + rnd.getAlgorithm + " ThreadSafe", "false")
        .toBoolean

    // If we can't sniff out a more optimal solution, we can always
    // fall back to a pool of blocking instances
    def fallbackPool: F[SecureRandom[F]] =
      for {
        n <- Sync[F].delay(Runtime.getRuntime.availableProcessors())
        sr <- javaSecuritySecureRandom(n)
      } yield sr

    javaMajorVersion.flatMap {
      case Some(major) if major > 8 =>
        happyRandom.redeemWith(
          _ =>
            fallback.flatMap {
              case rnd if isThreadsafe(rnd) =>
                // We avoided the mutex, but not the blocking.  Use a
                // shared instance from the blocking pool.
                javaUtilRandomBlocking(rnd)
              case _ =>
                // We can't prove the instance is threadsafe, so we need
                // to pessimistically fall back to a pool.  This should
                // be exceedingly uncommon.
                fallbackPool
            },
          // We are thread safe and non-blocking.  This is the
          // happy path, and happily, the common path.
          rnd => javaUtilRandomNonBlocking(rnd)
        )

      case Some(_) | None =>
        // We can't guarantee we're not stuck in a mutex.
        fallbackPool
    }
  }

  private def javaMajorVersion[F[_]: Sync]: F[Option[Int]] =
    Sync[F].delay(sys.props.get("java.version")).map(_.flatMap(parseJavaMajorVersion))

  private def parseJavaMajorVersion(javaVersion: String): Option[Int] =
    if (javaVersion.startsWith("1."))
      Try(javaVersion.split("\\.")(1).toInt).toOption
    else
      Try(javaVersion.split("\\.")(0).toInt).toOption

}
