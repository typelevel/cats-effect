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

package cats.effect.internals

import cats.data.{EitherT, StateT, WriterT}
import cats.effect.{Effect, Fiber, IO}
import cats.effect.internals.TrampolineEC.immediate
import cats.effect.internals.Conversions.{toEither, toTry}
import cats.kernel.Monoid

import scala.concurrent.Promise

/**
 * Internal API — the result of `Effect#start`.
 */
private[effect] final class EffectFiber[F[_], A](
  val join: F[A],
  val cancel: F[Unit])
  extends Fiber[F, A]

private[effect] object EffectFiber {
  /** Implementation for `cats.data.EitherT`. */
  def eitherT[F[_], A](fa: EitherT[F, Throwable, A])
    (implicit F: Effect[F]): EitherT[F, Throwable, Fiber[EitherT[F, Throwable, ?], A]] = {

    EitherT.liftF(F.delay {
      // Shared state
      val p = Promise[Either[Throwable, A]]()
      // Actual execution
      val cancel: IO[Unit] = F
        .runCancelable(fa.value)(r => IO(p.complete(toTry(r))))
        .unsafeRunSync()
      // Signals new F linked to the Promise above
      val forked =
        F.cancelable[Either[Throwable, A]] { cb =>
          p.future.onComplete(cb.compose(toEither))(immediate)
          cancel
        }
      // Builds fiber
      new EffectFiber(
        EitherT[F, Throwable, A](forked),
        EitherT.liftF[F, Throwable, Unit](cancel.to[F]))
    })
  }

  /** Implementation for `cats.data.StateT`. */
  def stateT[F[_], S, A](fa: StateT[F, S, A])
    (implicit F: Effect[F]): StateT[F, S, Fiber[StateT[F, S, ?], A]] = {

    StateT(startS => F.delay {
      // Shared state
      val p = Promise[(S, A)]()
      // Actual execution
      val cancel: IO[Unit] = F
        .runCancelable(fa.run(startS))(r => IO(p.complete(toTry(r))))
        .unsafeRunSync()
      // Signals new F linked to the Promise above
      val forked =
        F.cancelable[(S, A)] { cb =>
          p.future.onComplete(cb.compose(toEither))(immediate)
          cancel
        }
      // Builds fiber
      (startS, new EffectFiber(
        StateT[F, S, A](_ => forked),
        StateT.liftF[F, S, Unit](cancel.to[F])))
    })
  }

  /** Implementation for `cats.data.WriteT`. */
  def writerT[F[_], L, A](fa: WriterT[F, L, A])
    (implicit F: Effect[F], L: Monoid[L]): WriterT[F, L, Fiber[WriterT[F, L, ?], A]] = {

    WriterT.liftF(F.delay {
      // Shared state
      val p = Promise[(L, A)]()
      // Actual execution
      val cancel: IO[Unit] = F
        .runCancelable(fa.run)(r => IO(p.complete(toTry(r))))
        .unsafeRunSync()
      // Signals new F linked to the Promise above
      val forked =
        F.cancelable[(L, A)] { cb =>
          p.future.onComplete(cb.compose(toEither))(immediate)
          cancel
        }
      // Builds fiber
      new EffectFiber(
        WriterT[F, L, A](forked),
        WriterT.liftF[F, L, Unit](cancel.to[F]))
    })
  }
}