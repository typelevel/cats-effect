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
import scala.util.Either

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
   * Returns `true` if the underlying platform is the JVM,
   * `false` if it's JavaScript.
   */
  final val isJVM = true
}
