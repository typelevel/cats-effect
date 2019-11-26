/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats.effect.internals

import java.util.concurrent.locks.AbstractQueuedSynchronizer
import cats.effect.IO
import scala.concurrent.blocking
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Either, Try}

private[effect] object IOPlatform {

  /**
   * JVM-specific function that blocks for the result of an IO task.
   *
   * Uses the [[scala.concurrent.BlockContext]], instructing the
   * underlying thread-pool that this is a blocking operation, thus
   * allowing for defensive measures, like adding more threads or
   * executing other queued tasks first.
   */
  def unsafeResync[A](ioa: IO[A], limit: Duration): Option[A] = {
    val latch = new OneShotLatch
    var ref: Either[Throwable, A] = null

    ioa.unsafeRunAsync { a =>
      // Reading from `ref` happens after the block on `latch` is
      // over, there's a happens-before relationship, so no extra
      // synchronization is needed for visibility
      ref = a
      latch.releaseShared(1)
      ()
    }

    limit match {
      case e if e eq Duration.Undefined =>
        throw new IllegalArgumentException("Cannot wait for Undefined period")
      case Duration.Inf =>
        blocking(latch.acquireSharedInterruptibly(1))
      case f: FiniteDuration if f > Duration.Zero =>
        blocking(latch.tryAcquireSharedNanos(1, f.toNanos))
      case _ =>
        () // Do nothing
    }

    ref match {
      case null     => None
      case Right(a) => Some(a)
      case Left(ex) => throw ex
    }
  }

  final private class OneShotLatch extends AbstractQueuedSynchronizer {
    override protected def tryAcquireShared(ignored: Int): Int =
      if (getState != 0) 1 else -1

    override protected def tryReleaseShared(ignore: Int): Boolean = {
      setState(1)
      true
    }
  }

  /**
   * Establishes the maximum stack depth for `IO#map` operations.
   *
   * The default is `128`, from which we subtract one as an
   * optimization. This default has been reached like this:
   *
   *  - according to official docs, the default stack size on 32-bits
   *    Windows and Linux was 320 KB, whereas for 64-bits it is 1024 KB
   *  - according to measurements chaining `Function1` references uses
   *    approximately 32 bytes of stack space on a 64 bits system;
   *    this could be lower if "compressed oops" is activated
   *  - therefore a "map fusion" that goes 128 in stack depth can use
   *    about 4 KB of stack space
   *
   * If this parameter becomes a problem, it can be tuned by setting
   * the `cats.effect.fusionMaxStackDepth` system property when
   * executing the Java VM:
   *
   * <pre>
   *   java -Dcats.effect.fusionMaxStackDepth=32 \
   *        ...
   * </pre>
   */
  final val fusionMaxStackDepth =
    Option(System.getProperty("cats.effect.fusionMaxStackDepth", ""))
      .filter(s => s != null && s.nonEmpty)
      .flatMap(s => Try(s.toInt).toOption)
      .filter(_ > 0)
      .map(_ - 1)
      .getOrElse(127)

  /**
   * Returns `true` if the underlying platform is the JVM,
   * `false` if it's JavaScript.
   */
  final val isJVM = true
}
