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

import cats.effect.concurrent.Deferred
import cats.implicits._
import cats.laws._

trait ConcurrentEffectLaws[F[_]] extends ConcurrentLaws[F] with EffectLaws[F] {
  implicit def F: ConcurrentEffect[F]

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

  def runCancelableStartCancelCoherence[A](a: A) = {
    // Cancellation via runCancelable
    val f1 = Deferred.uncancelable[IO, A].flatMap { effect1 =>
      val never = F.cancelable[A](_ => effect1.complete(a))
      F.runCancelable(never)(_ => IO.unit).flatten *> effect1.get
    }
    // Cancellation via start.flatMap(_.cancel)
    val f2 = for {
      effect2 <- Deferred.uncancelable[IO, A]
      // Using a latch to ensure that the task started
      await   <- Deferred.uncancelable[IO, A]
      awaitF   = F.liftIO(await.complete(a))
      never   =  F.bracket(awaitF)(_ => F.never[Unit])(_ => F.liftIO(effect2.complete(a)))
      _       <- F.runAsync(F.start(never).flatMap(_.cancel))(_ => IO.unit)
      result  <- effect2.get
    } yield result

    f1 <-> f2
  }

  def toIORunCancelableConsistency[A](fa: F[A]) =
    ConcurrentEffect.toIOFromRunCancelable(fa) <-> F.toIO(fa)
}

object ConcurrentEffectLaws {
  def apply[F[_]](implicit F0: ConcurrentEffect[F], timer0: Timer[F]): ConcurrentEffectLaws[F] = new ConcurrentEffectLaws[F] {
    val F = F0
    val timer = timer0
  }
}
