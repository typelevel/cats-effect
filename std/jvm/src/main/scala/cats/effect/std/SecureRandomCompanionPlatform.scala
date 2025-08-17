/*
 * Copyright 2020-2025 Typelevel
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

import cats.effect.kernel._
import cats.effect.std.Random.ScalaRandom

import scala.util.{Random => SRandom, Try}
import scala.util.control.NonFatal

import java.util.concurrent.atomic.AtomicInteger

private[std] trait SecureRandomCompanionPlatform {
  private[std] type JavaSecureRandom = java.security.SecureRandom
  private def getInstance: JavaSecureRandom =
    java.security.SecureRandom.getInstance("NativePRNGNonBlocking")

  private def javaUtilRandom[F[_]: Sync](random: JavaSecureRandom): SecureRandom[F] =
    new ScalaRandom[F](Applicative[F].pure(random)) with SecureRandom[F] {}

  /**
   * Creates a blocking Random instance.
   *
   * @param random
   *   a potentially blocking instance of java.util.Random
   */
  private def javaUtilRandomBlocking[F[_]: Sync](random: JavaSecureRandom): SecureRandom[F] =
    new ScalaRandom[F](Applicative[F].pure(random), Sync.Type.Blocking) with SecureRandom[F] {}

  def javaSecuritySecureRandom[F[_]: Sync]: F[SecureRandom[F]] =
    Sync[F].delay(unsafeJavaSecuritySecureRandom())

  /**
   * Ported from https://github.com/http4s/http4s/.
   */
  private[effect] def unsafeJavaSecuritySecureRandom[F[_]: Sync](): SecureRandom[F] = {
    // This is a known, non-blocking, threadsafe algorithm
    def happyRandom = getInstance

    def fallback = new JavaSecureRandom()

    // Porting JavaSecureRandom.isThreadSafe
    def isThreadsafe(rnd: JavaSecureRandom) =
      rnd
        .getProvider
        .getProperty("SecureRandom." + rnd.getAlgorithm + " ThreadSafe", "false")
        .toBoolean

    // If we can't sniff out a more optimal solution, we can always
    // fall back to a pool of blocking instances
    def fallbackPool: SecureRandom[F] = {
      val processors = Runtime.getRuntime.availableProcessors()
      pool(processors)
    }

    javaMajorVersion match {
      case Some(major) if major > 8 =>
        try {
          // We are thread safe and non-blocking.  This is the
          // happy path, and happily, the common path.
          javaUtilRandom(happyRandom)
        } catch {
          case ex if NonFatal(ex) =>
            fallback match {
              case rnd if isThreadsafe(rnd) =>
                // We avoided the mutex, but not the blocking.  Use a
                // shared instance from the blocking pool.
                javaUtilRandomBlocking(rnd)
              case _ =>
                // We can't prove the instance is threadsafe, so we need
                // to pessimistically fall back to a pool.  This should
                // be exceedingly uncommon.
                fallbackPool
            }
        }

      case Some(_) | None =>
        // We can't guarantee we're not stuck in a mutex.
        fallbackPool
    }
  }

  private def pool[F[_]: Sync](n: Int): SecureRandom[F] = {
    val index = new AtomicInteger(0)
    val array = Array.fill(n)(new SRandom(new SecureRandom.JavaSecureRandom))

    def selectRandom: F[SRandom] = Sync[F].delay {
      val currentIndex = index.getAndUpdate(i => (i + 1) % n)
      array(currentIndex)
    }

    new ScalaRandom[F](selectRandom) with SecureRandom[F] {}
  }

  private def javaMajorVersion: Option[Int] =
    Option(System.getProperty("java.version")).flatMap(parseJavaMajorVersion)

  private def parseJavaMajorVersion(javaVersion: String): Option[Int] =
    if (javaVersion.startsWith("1."))
      Try(javaVersion.split("\\.")(1).toInt).toOption
    else
      Try(javaVersion.split("\\.")(0).toInt).toOption

}
