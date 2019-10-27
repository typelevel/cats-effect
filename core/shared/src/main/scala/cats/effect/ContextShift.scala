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

package cats.effect

import cats.{~>, Applicative, Functor, Monad, Monoid}
import cats.data._
import scala.annotation.implicitNotFound
import scala.concurrent.ExecutionContext

/**
 * ContextShift provides support for shifting execution.
 *
 * The `shift` method inserts an asynchronous boundary, which moves execution
 * from the calling thread to the default execution environment of `F`.
 *
 * The `evalOn` method provides a way to evaluate a task on a specific execution
 * context, shifting back to the default execution context after the task completes.
 *
 * This is NOT a type class, as it does not have the coherence
 * requirement.
 */
@implicitNotFound("""Cannot find an implicit value for ContextShift[${F}]:
* import ContextShift[${F}] from your effects library
* if using IO, use cats.effect.IOApp or build one with cats.effect.IO.contextShift
""")
trait ContextShift[F[_]] {

  /**
   * Asynchronous boundary described as an effectful `F[_]` that
   * can be used in `flatMap` chains to "shift" the continuation
   * of the run-loop to another thread or call stack.
   *
   * This is the [[Async.shift]] operation, without the need for an
   * `ExecutionContext` taken as a parameter.
   *
   */
  def shift: F[Unit]

  /**
   * Evaluates `fa` on the supplied blocker and shifts evaluation
   * back to the default execution environment of `F` at the completion of `fa`,
   * regardless of success or failure.
   *
   * The primary use case for this method is executing blocking code on a
   * dedicated execution context.
   *
   * @param blocker blocker where the evaluation has to be scheduled
   * @param fa  Computation to evaluate using `blocker`
   */
  def blockOn[A](blocker: Blocker)(fa: F[A]): F[A] =
    evalOn(blocker.blockingContext)(fa)

  /**
   * Evaluates `fa` on the supplied execution context and shifts evaluation
   * back to the default execution environment of `F` at the completion of `fa`,
   * regardless of success or failure.
   *
   * The primary use case for this method is executing code on a
   * specific execution context. To execute blocking code, consider using
   * the `blockOn(blocker)` method instead.
   *
   * @param ec Execution context where the evaluation has to be scheduled
   * @param fa  Computation to evaluate using `ec`
   */
  def evalOn[A](ec: ExecutionContext)(fa: F[A]): F[A]
}

object ContextShift {
  def apply[F[_]](implicit ev: ContextShift[F]): ContextShift[F] = ev

  /**
   * `evalOn` as a natural transformation.
   */
  def evalOnK[F[_]](ec: ExecutionContext)(implicit cs: ContextShift[F]): F ~> F = λ[F ~> F](cs.evalOn(ec)(_))

  /**
   * Derives a [[ContextShift]] instance for `cats.data.EitherT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveEitherT[F[_], L](implicit F: Functor[F], cs: ContextShift[F]): ContextShift[EitherT[F, L, *]] =
    new ContextShift[EitherT[F, L, *]] {
      def shift: EitherT[F, L, Unit] =
        EitherT.liftF(cs.shift)

      def evalOn[A](ec: ExecutionContext)(fa: EitherT[F, L, A]): EitherT[F, L, A] =
        EitherT(cs.evalOn(ec)(fa.value))
    }

  /**
   * Derives a [[ContextShift]] instance for `cats.data.OptionT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveOptionT[F[_]](implicit F: Functor[F], cs: ContextShift[F]): ContextShift[OptionT[F, *]] =
    new ContextShift[OptionT[F, *]] {
      def shift: OptionT[F, Unit] =
        OptionT.liftF(cs.shift)

      def evalOn[A](ec: ExecutionContext)(fa: OptionT[F, A]): OptionT[F, A] =
        OptionT(cs.evalOn(ec)(fa.value))
    }

  /**
   * Derives a [[ContextShift]] instance for `cats.data.WriterT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveWriterT[F[_], L](implicit F: Applicative[F],
                                      L: Monoid[L],
                                      cs: ContextShift[F]): ContextShift[WriterT[F, L, *]] =
    new ContextShift[WriterT[F, L, *]] {
      def shift: WriterT[F, L, Unit] =
        WriterT.liftF(cs.shift)

      def evalOn[A](ec: ExecutionContext)(fa: WriterT[F, L, A]): WriterT[F, L, A] =
        WriterT(cs.evalOn(ec)(fa.run))
    }

  /**
   * Derives a [[ContextShift]] instance for `cats.data.StateT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveStateT[F[_], L](implicit F: Monad[F], cs: ContextShift[F]): ContextShift[StateT[F, L, *]] =
    new ContextShift[StateT[F, L, *]] {
      def shift: StateT[F, L, Unit] =
        StateT.liftF(cs.shift)

      def evalOn[A](ec: ExecutionContext)(fa: StateT[F, L, A]): StateT[F, L, A] =
        StateT(s => cs.evalOn(ec)(fa.run(s)))
    }

  /**
   * Derives a [[ContextShift]] instance for `cats.data.Kleisli`,
   * given we have one for `F[_]`.
   */
  implicit def deriveKleisli[F[_], R](implicit cs: ContextShift[F]): ContextShift[Kleisli[F, R, *]] =
    new ContextShift[Kleisli[F, R, *]] {
      def shift: Kleisli[F, R, Unit] =
        Kleisli.liftF(cs.shift)

      def evalOn[A](ec: ExecutionContext)(fa: Kleisli[F, R, A]): Kleisli[F, R, A] =
        Kleisli(a => cs.evalOn(ec)(fa.run(a)))
    }

  /**
   * Derives a [[ContextShift]] instance for `cats.data.IorT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveIorT[F[_], L](implicit F: Applicative[F], cs: ContextShift[F]): ContextShift[IorT[F, L, *]] =
    new ContextShift[IorT[F, L, *]] {
      def shift: IorT[F, L, Unit] =
        IorT.liftF(cs.shift)

      def evalOn[A](ec: ExecutionContext)(fa: IorT[F, L, A]): IorT[F, L, A] =
        IorT(cs.evalOn(ec)(fa.value))
    }
}
