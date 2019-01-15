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

package cats
package effect
package laws

import cats.laws._

trait EffectLaws[F[_]] extends AsyncLaws[F] {
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
    F.runAsync(fa)(_ => IO.raiseError(e)).toIO <-> IO.pure(())
  }

  def toIOinverseOfLiftIO[A](ioa: IO[A]) =
    F.toIO(F.liftIO(ioa)) <-> ioa

  def toIORunAsyncConsistency[A](fa: F[A]) =
    Effect.toIOFromRunAsync(fa) <-> F.toIO(fa)

  def toIOStackSafety[A](iterations: Int)(fa: F[A]) = {
    def loop(n: Int): IO[A] =
      if (n > 0) F.toIO(fa).flatMap(_ => loop(n - 1))
      else F.toIO(fa)

    loop(iterations) <-> F.toIO(fa)
  }
}

object EffectLaws {
  def apply[F[_]](implicit F0: Effect[F]): EffectLaws[F] = new EffectLaws[F] {
    val F = F0
  }
}
