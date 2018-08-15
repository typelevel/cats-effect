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

package cats.effect

import cats.{Applicative, Functor, Monad, Monoid}
import cats.data._

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
   * Evaluates `f` on the supplied execution context and shifts evaluation
   * back to the default execution environment of `F` at the completion of `f`,
   * regardless of success or failure.
   *
   * The primary use case for this method is executing blocking code on a
   * dedicated execution context.
   *
   * @param context  Execution content where the `f` has to be scheduled
   * @param f        Computation to rin on `context`
   */
  def evalOn[A](context: ExecutionContext)(f: F[A]): F[A]
}

object ContextShift {
  /**
   * Derives a [[ContextShift]] instance for `cats.data.EitherT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveEitherT[F[_], L](implicit F: Functor[F], cs: ContextShift[F]): ContextShift[EitherT[F, L, ?]] =
    new ContextShift[EitherT[F, L, ?]] {
      def shift: EitherT[F, L, Unit] =
        EitherT.liftF(cs.shift)

      def evalOn[A](context: ExecutionContext)(f: EitherT[F, L, A]): EitherT[F, L, A] =
        EitherT(cs.evalOn(context)(f.value))
    }

  /**
   * Derives a [[ContextShift]] instance for `cats.data.OptionT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveOptionT[F[_]](implicit F: Functor[F], cs: ContextShift[F]): ContextShift[OptionT[F, ?]] =
    new ContextShift[OptionT[F, ?]] {
      def shift: OptionT[F, Unit] =
        OptionT.liftF(cs.shift)

      def evalOn[A](context: ExecutionContext)(f: OptionT[F, A]): OptionT[F, A] =
        OptionT(cs.evalOn(context)(f.value))
    }

  /**
   * Derives a [[ContextShift]] instance for `cats.data.WriterT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveWriterT[F[_], L](implicit F: Applicative[F], L: Monoid[L], cs: ContextShift[F]): ContextShift[WriterT[F, L, ?]] =
    new ContextShift[WriterT[F, L, ?]] {
      def shift: WriterT[F, L, Unit] =
        WriterT.liftF(cs.shift)

      def evalOn[A](context: ExecutionContext)(f: WriterT[F, L, A]): WriterT[F, L, A] =
        WriterT(cs.evalOn(context)(f.run))
    }

  /**
   * Derives a [[ContextShift]] instance for `cats.data.StateT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveStateT[F[_], L](implicit F: Monad[F], cs: ContextShift[F]): ContextShift[StateT[F, L, ?]] =
    new ContextShift[StateT[F, L, ?]] {
      def shift: StateT[F, L, Unit] =
        StateT.liftF(cs.shift)

      def evalOn[A](context: ExecutionContext)(f: StateT[F, L, A]): StateT[F, L, A] =
        StateT(s => cs.evalOn(context)(f.run(s)))
    }

  /**
   * Derives a [[ContextShift]] instance for `cats.data.Kleisli`,
   * given we have one for `F[_]`.
   */
  implicit def deriveKleisli[F[_], R](implicit cs: ContextShift[F]): ContextShift[Kleisli[F, R, ?]] =
    new ContextShift[Kleisli[F, R, ?]] {
      def shift: Kleisli[F, R, Unit] =
        Kleisli.liftF(cs.shift)

      def evalOn[A](context: ExecutionContext)(f: Kleisli[F, R, A]): Kleisli[F, R, A] =
        Kleisli(a => cs.evalOn(context)(f.run(a)))
    }
}
