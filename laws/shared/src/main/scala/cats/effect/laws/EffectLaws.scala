/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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
package laws

import cats.implicits._
import cats.laws._

trait EffectLaws[F[_]] extends AsyncStartLaws[F] {
  implicit def F: Effect[F]

  def runAsyncPureProducesRightIO[A](a: A) = {
    val lh = IO.async[Either[Throwable, A]] { cb =>
      F.runAsync(F.pure(a))(r => IO(cb(Right(r))))
        .unsafeRunSync()
    }
    lh <-> IO.pure(Right(a))
  }

  def runAsyncRaiseErrorProducesLeftIO[A](e: Throwable) = {
    val lh = IO.async[Either[Throwable, A]] { cb =>
      F.runAsync(F.raiseError(e))(r => IO(cb(Right(r))))
        .unsafeRunSync()
    }
    lh <-> IO.pure(Left(e))
  }

  def runAsyncIgnoresErrorInHandler[A](e: Throwable) = {
    val fa = F.pure(())
    F.runAsync(fa)(_ => IO.raiseError(e)) <-> IO.pure(())
  }

  def runAsyncRunCancelableCoherence[A](fa: F[A]) = {
    val fa1 = IO.async[A] { cb => F.runAsync(fa)(r => IO(cb(r))).unsafeRunSync() }
    val fa2 = IO.cancelable[A] { cb => F.runCancelable(fa)(r => IO(cb(r))).unsafeRunSync() }
    fa1 <-> fa2
  }

  def runCancelableIsSynchronous[A](fa: F[A]) = {
    // Creating never ending tasks
    def never[T] = IO.async[T](_ => {})
    val ff = F.cancelable[A](_ => F.runAsync(fa)(_ => never))

    val lh = IO(F.runCancelable(ff)(_ => never).unsafeRunSync().unsafeRunSync())
    lh <-> IO.unit
  }

  def runCancelableStartCancelCoherence[A](a: A, f: (A, A) => A) = {
    // Cancellation via runCancelable
    val f1 = F.delay {
      var effect = a
      val never = F.cancelable[A](_ => IO { effect = f(effect, a) })
      F.runCancelable(never)(_ => IO.unit).unsafeRunSync().unsafeRunSync()
      effect
    }
    // Cancellation via start.flatMap(_.cancel)
    val f2 = F.suspend {
      var effect = a
      val never = F.cancelable[A](_ => IO { effect = f(effect, a) })
      F.start(never).flatMap(_.cancel).map(_ => effect)
    }
    f1 <-> f2
  }
}

object EffectLaws {
  def apply[F[_]](implicit F0: Effect[F]): EffectLaws[F] = new EffectLaws[F] {
    val F = F0
  }
}
