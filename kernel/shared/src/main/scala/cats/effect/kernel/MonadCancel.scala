/*
 * Copyright 2020 Typelevel
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

package cats.effect.kernel

import cats.{MonadError, Monoid, Semigroup}
import cats.data.{
  EitherT,
  IndexedReaderWriterStateT,
  IndexedStateT,
  IorT,
  Kleisli,
  OptionT,
  ReaderWriterStateT,
  StateT,
  WriterT
}
import cats.syntax.all._

// TODO: talk about cancellation boundaries
/**
 * A typeclass that characterizes monads which support safe cancellation,
 * masking, and finalization. [[MonadCancel]] extends the capabilities of
 * [[MonadError]], so an instance of this typeclass must also provide a lawful
 * instance for [[MonadError]].
 *
 * ==Fibers==
 *
 * A fiber is a sequence of effects which are bound together by [[flatMap]].
 * The execution of a fiber of an effect `F[E, A]` terminates with one of three
 * outcomes, which are encoded by the datatype [[Outcome]]:
 *
 *   1. [[Completed]]: indicates success with a value of type `A`
 *   1. [[Errored]]: indicates failure with a value of type `E`
 *   1. [[Canceled]]: indicates abnormal termination
 *
 * Additionally, a fiber may never produce an outcome, in which case it is said
 * to be non-terminating.
 *
 * ==Cancellation==
 *
 * Cancellation refers to the act of requesting that the execution of a fiber
 * be abnormally terminated. [[MonadCancel]] exposes a means of
 * self-cancellation, with which a fiber can request that its own execution
 * be terminated. Self-cancellation is achieved via
 * [[MonadCancel!.canceled canceled]].
 *
 * Cancellation is vaguely similar to the short-circuiting behavior introduced
 * by [[MonadError]], but there are several key differences:
 *
 *   1. Cancellation is effective; if it is observed it must be respected, and
 *      it cannot be reversed. In contrast, [[MonadError!handleError handleError]]
 *      exposes the ability to catch and recover from errors, and then proceed
 *      with normal execution.
 *   1. Cancellation can be masked via [[MonadCancel!.uncancelable]]. Masking
 *      is discussed in the next section.
 *   1. [[GenSpawn]] introduces external cancellation, another cancellation
 *      mechanism by which fibers can be cancelled by external parties.
 *
 * ==Masking==
 *
 * Masking allows a fiber to suppress cancellation for a period of time, which
 * is achieved via [[MonadCancel!.uncancelable uncancelable]]. If a fiber is
 * cancelled while it is masked, the cancellation is suppressed for as long as
 * the fiber remains masked. Once the fiber reaches a completely unmasked
 * state, it responds to the cancellation.
 *
 * While a fiber is masked, it may optionally unmask by "polling", rendering
 * itself cancelable again.
 *
 * {{{
 *
 *   F.uncancelable { poll =>
 *     // can only observe cancellation within `fb`
 *     fa *> poll(fb) *> fc
 *   }
 *
 * }}}
 *
 * These semantics allow users to precisely mark what regions of code are
 * cancelable within a larger code block.
 *
 * ==Finalization==
 *
 * Finalization refers to the act of running finalizers in response to a
 * cancellation. Finalizers are those effects whose evaluation is guaranteed
 * in the event of cancellation. After a fiber has completed finalization,
 * it terminates with an outcome of `Canceled`.
 *
 * Finalizers can be registered to a fiber for the duration of some effect via
 * [[MonadCancel!.onCancel onCancel]]. If a fiber is cancelled while running
 * that effect, the registered finalizer is guaranteed to be run before
 * terminating.
 *
 * ==Bracket pattern==
 *
 * The aforementioned concepts work together to unlock a powerful pattern for
 * safely interacting with effectful lifecycles: the bracket pattern. This is
 * analogous to the try-with-resources/finally construct in Java.
 *
 * A lifecycle refers to a pair of actions, which are called the acquisition
 * action and the release action respectively. The relationship between these
 * two actions is that if the former completes successfully, then the latter is
 * guaranteed to be run eventually, even in the presence of errors and
 * cancellation. While the lifecycle is active, other work can be performed, but
 * this invariant is always respected.
 *
 * The bracket pattern is an invaluable tool for safely handling resource
 * lifecycles. Imagine an application that opens network connections to a
 * database server to do work. If a task in the application is cancelled while
 * it holds an open database connection, the connection would never be released
 * or returned to a pool, causing a resource leak.
 *
 * To illustrate the compositional nature of [[MonadCancel]] and its combinators,
 * the implementation of [[MonadCancel!.bracket bracket]] is shown below:
 *
 * {{{
 *
 *   def bracket[A, B](acquire: F[A])(use: A => F[B])(release: A => F[Unit]): F[B] =
 *     uncancelable { poll =>
 *       flatMap(acquire) { a =>
 *         val finalized = onCancel(poll(use(a)), release(a))
 *         val handled = onError(finalized) { case e => void(attempt(release(a))) }
 *         flatMap(handled)(b => as(attempt(release(a)), b))
 *       }
 *     }
 *
 * }}}
 *
 * See [[MonadCancel!.bracketCase bracketCase]] and [[MonadCancel!.bracketFull bracketFull]]
 * for other variants of the bracket pattern. If more specialized behavior is
 * necessary, it is recommended to use [[MonadCancel!.uncancelable uncancelable]]
 * and [[MonadCancel!.onCancel onCancel]] directly.
 */
trait MonadCancel[F[_], E] extends MonadError[F, E] {

  /**
   * Analogous to [[productR]], but suppresses short-circuiting behavior
   * except for cancellation.
   */
  def forceR[A, B](fa: F[A])(fb: F[B]): F[B]

  /**
   * Masks cancellation on the current fiber. The argument to `body` of type
   * `Poll[F]` is a natural transformation `F ~> F` that enables polling.
   * Polling causes a fiber to unmask within a masked region so that
   * cancellation can be observed again.
   *
   * In the following example, cancellation can be observed only within `fb`
   * and nowhere else:
   *
   * {{{
   *
   *   F.uncancelable { poll =>
   *     fa *> poll(fb) *> fc
   *   }
   *
   * }}}
   *
   * If a fiber is cancelled while it is masked, the cancellation is suppressed
   * for as long as the fiber remains masked. Whenever the fiber is completely
   * unmasked again, the cancellation will be respected.
   *
   * Masks can also be stacked or nested within each other. If multiple masks
   * are active, all masks must be undone so that cancellation can be observed.
   * In order to completely unmask within a multi-masked region, the poll
   * corresponding to each mask must be applied, innermost-first.
   *
   * {{{
   *
   *   F.uncancelable { p1 =>
   *     F.uncancelable { p2 =>
   *       fa *> p2(p1(fb)) *> fc
   *     }
   *   }
   *
   * }}}
   *
   * The following operations are no-ops:
   *
   *   1. Polling in the wrong order
   *   1. Applying the same poll more than once: `poll(poll(fa))`
   *   1. Applying a poll bound to one fiber within another fiber
   */
  def uncancelable[A](body: Poll[F] => F[A]): F[A]

  /**
   * An effect that requests self-cancellation on the current fiber.
   *
   * In the following example, the fiber requests self-cancellation in a masked
   * region, so cancellation is suppressed until the fiber is completely
   * unmasked. `fa` will run but `fb` will not.
   *
   * {{{
   *
   *   F.uncancelable { _ =>
   *     F.canceled *> fa
   *   } *> fb
   *
   * }}}
   */
  def canceled: F[Unit]

  /**
   * Registers a finalizer that is invoked if cancellation is observed
   * during the evaluation of `fa`. If the evaluation of `fa` completes
   * without encountering a cancellation, the finalizer is unregistered
   * before proceeding.
   *
   * During finalization, all actively registered finalizers are run exactly
   * once. The order by which finalizers are run is dictated by nesting:
   * innermost finalizers are run before outermost finalizers. For example,
   * in the following program, the finalizer `f1` is run before the finalizer
   * `f2`:
   *
   * {{{
   *
   *   F.onCancel(F.onCancel(F.canceled, f1), f2)
   *
   * }}}
   *
   * If a finalizer throws an error during evaluation, the error is suppressed,
   * and implementations may choose to report it via a side channel. Finalizers
   * are always uncancelable, so cannot otherwise be interrupted.
   *
   * @param fa The effect that is evaluated after `fin` is registered.
   * @param fin The finalizer to register before evaluating `fa`.
   */
  def onCancel[A](fa: F[A], fin: F[Unit]): F[A]

  /**
   * Specifies an effect that is always invoked after evaluation of `fa`
   * completes, regardless of the outcome.
   *
   * This function can be thought of as a combination of
   * [[Monad!.flatTap flatTap]], [[MonadError!.onError onError]], and
   * [[MonadCancel!.onCancel onCancel]].
   *
   * @param fa The effect that is run after `fin` is registered.
   * @param fin The effect to run in the event of a cancellation or error.
   *
   * @see [[guaranteeCase]] for a more powerful variant
   *
   * @see [[Outcome]] for the various outcomes of evaluation
   */
  def guarantee[A](fa: F[A], fin: F[Unit]): F[A] =
    guaranteeCase(fa)(_ => fin)

  /**
   * Specifies an effect that is always invoked after evaluation of `fa`
   * completes, but depends on the outcome.
   *
   * This function can be thought of as a combination of
   * [[Monad!.flatTap flatTap]], [[MonadError!.onError onError]], and
   * [[MonadCancel!.onCancel onCancel]].
   *
   * @param fa The effect that is run after `fin` is registered.
   * @param fin A function that returns the effect to run based on the
   *        outcome.
   *
   * @see [[bracketCase]] for a more powerful variant
   *
   * @see [[Outcome]] for the various outcomes of evaluation
   */
  def guaranteeCase[A](fa: F[A])(fin: Outcome[F, E, A] => F[Unit]): F[A] =
    bracketCase(unit)(_ => fa)((_, oc) => fin(oc))

  /**
   * A pattern for safely interacting with effectful lifecycles.
   *
   * If `acquire` completes successfully, `use` is called. If `use` succeeds,
   * fails, or is cancelled, `release` is guaranteed to be called exactly once.
   *
   * `acquire` is uncancelable.
   * `release` is uncancelable.
   * `use` is cancelable by default, but can be masked.
   *
   * @param acquire the lifecycle acquisition action
   * @param use the effect to which the lifecycle is scoped, whose result
   *            is the return value of this function
   * @param release the lifecycle release action
   *
   * @see [[bracketCase]] for a more powerful variant
   *
   * @see [[Resource]] for a composable datatype encoding of effectful lifecycles
   */
  def bracket[A, B](acquire: F[A])(use: A => F[B])(release: A => F[Unit]): F[B] =
    bracketCase(acquire)(use)((a, _) => release(a))

  /**
   * A pattern for safely interacting with effectful lifecycles.
   *
   * If `acquire` completes successfully, `use` is called. If `use` succeeds,
   * fails, or is cancelled, `release` is guaranteed to be called exactly once.
   *
   * `acquire` is uncancelable.
   * `release` is uncancelable.
   * `use` is cancelable by default, but can be masked.
   *
   * `acquire` and `release` are both uncancelable, whereas `use` is cancelable
   * by default.
   *
   * @param acquire the lifecycle acquisition action
   * @param use the effect to which the lifecycle is scoped, whose result
   *            is the return value of this function
   * @param release the lifecycle release action which depends on the outcome of `use`
   *
   * @see [[bracketFull]] for a more powerful variant
   *
   * @see [[Resource]] for a composable datatype encoding of effectful lifecycles
   */
  def bracketCase[A, B](acquire: F[A])(use: A => F[B])(
      release: (A, Outcome[F, E, B]) => F[Unit]): F[B] =
    bracketFull(_ => acquire)(use)(release)

  /**
   * A pattern for safely interacting with effectful lifecycles.
   *
   * If `acquire` completes successfully, `use` is called. If `use` succeeds,
   * fails, or is cancelled, `release` is guaranteed to be called exactly once.
   *
   * If `use` succeeds the returned value `B` is returned. If `use` returns
   * an exception, the exception is returned.
   *
   * `acquire` is uncancelable by default, but can be unmasked.
   * `release` is uncancelable.
   * `use` is cancelable by default, but can be masked.
   *
   * @param acquire the lifecycle acquisition action which can be cancelled
   * @param use the effect to which the lifecycle is scoped, whose result
   *            is the return value of this function
   * @param release the lifecycle release action which depends on the outcome of `use`
   */
  def bracketFull[A, B](acquire: Poll[F] => F[A])(use: A => F[B])(
      release: (A, Outcome[F, E, B]) => F[Unit]): F[B] =
    uncancelable { poll =>
      flatMap(acquire(poll)) { a =>
        val finalized = onCancel(poll(use(a)), release(a, Outcome.Canceled()))
        val handled = onError(finalized) {
          case e => void(attempt(release(a, Outcome.Errored(e))))
        }
        flatMap(handled)(b => as(attempt(release(a, Outcome.Completed(pure(b)))), b))
      }
    }
}

object MonadCancel {

  def apply[F[_], E](implicit F: MonadCancel[F, E]): F.type = F
  def apply[F[_]](implicit F: MonadCancel[F, _], d: DummyImplicit): F.type = F

  implicit def monadCancelForOptionT[F[_], E](
      implicit F0: MonadCancel[F, E]): MonadCancel[OptionT[F, *], E] =
    new OptionTMonadCancel[F, E] {

      override implicit protected def F: MonadCancel[F, E] = F0
    }

  implicit def monadCancelForEitherT[F[_], E0, E](
      implicit F0: MonadCancel[F, E]): MonadCancel[EitherT[F, E0, *], E] =
    new EitherTMonadCancel[F, E0, E] {

      override implicit protected def F: MonadCancel[F, E] = F0
    }

  implicit def monadCancelForKleisli[F[_], R, E](
      implicit F0: MonadCancel[F, E]): MonadCancel[Kleisli[F, R, *], E] =
    new KleisliMonadCancel[F, R, E] {

      override implicit protected def F: MonadCancel[F, E] = F0
    }

  implicit def monadCancelForIorT[F[_], L, E](
      implicit F0: MonadCancel[F, E],
      L0: Semigroup[L]): MonadCancel[IorT[F, L, *], E] =
    new IorTMonadCancel[F, L, E] {

      override implicit protected def F: MonadCancel[F, E] = F0

      override implicit protected def L: Semigroup[L] = L0
    }

  implicit def monadCancelForWriterT[F[_], L, E](
      implicit F0: MonadCancel[F, E],
      L0: Monoid[L]): MonadCancel[WriterT[F, L, *], E] =
    new WriterTMonadCancel[F, L, E] {

      override implicit protected def F: MonadCancel[F, E] = F0

      override implicit protected def L: Monoid[L] = L0
    }

  implicit def monadCancelForStateT[F[_], S, E](
      implicit F0: MonadCancel[F, E]): MonadCancel[StateT[F, S, *], E] =
    new StateTMonadCancel[F, S, E] {
      override implicit protected def F = F0
    }

  implicit def monadCancelForReaderWriterStateT[F[_], E0, L, S, E](
      implicit F0: MonadCancel[F, E],
      L0: Monoid[L]): MonadCancel[ReaderWriterStateT[F, E0, L, S, *], E] =
    new ReaderWriterStateTMonadCancel[F, E0, L, S, E] {
      override implicit protected def F = F0
      override implicit protected def L = L0
    }

  private[kernel] trait OptionTMonadCancel[F[_], E] extends MonadCancel[OptionT[F, *], E] {
    implicit protected def F: MonadCancel[F, E]

    protected def delegate: MonadError[OptionT[F, *], E] =
      OptionT.catsDataMonadErrorForOptionT[F, E]

    def uncancelable[A](body: Poll[OptionT[F, *]] => OptionT[F, A]): OptionT[F, A] =
      OptionT(
        F.uncancelable { nat =>
          val natT = new Poll[OptionT[F, *]] {
            def apply[B](optfa: OptionT[F, B]): OptionT[F, B] = OptionT(nat(optfa.value))
          }
          body(natT).value
        }
      )

    def canceled: OptionT[F, Unit] = OptionT.liftF(F.canceled)

    def onCancel[A](fa: OptionT[F, A], fin: OptionT[F, Unit]): OptionT[F, A] =
      OptionT(F.onCancel(fa.value, fin.value.void))

    def forceR[A, B](fa: OptionT[F, A])(fb: OptionT[F, B]): OptionT[F, B] =
      OptionT(
        F.forceR(fa.value)(fb.value)
      )

    def pure[A](a: A): OptionT[F, A] = delegate.pure(a)

    def raiseError[A](e: E): OptionT[F, A] = delegate.raiseError(e)

    def handleErrorWith[A](fa: OptionT[F, A])(f: E => OptionT[F, A]): OptionT[F, A] =
      delegate.handleErrorWith(fa)(f)

    def flatMap[A, B](fa: OptionT[F, A])(f: A => OptionT[F, B]): OptionT[F, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => OptionT[F, Either[A, B]]): OptionT[F, B] =
      delegate.tailRecM(a)(f)
  }

  private[kernel] trait EitherTMonadCancel[F[_], E0, E]
      extends MonadCancel[EitherT[F, E0, *], E] {

    implicit protected def F: MonadCancel[F, E]

    protected def delegate: MonadError[EitherT[F, E0, *], E] =
      EitherT.catsDataMonadErrorFForEitherT[F, E, E0]

    def uncancelable[A](body: Poll[EitherT[F, E0, *]] => EitherT[F, E0, A]): EitherT[F, E0, A] =
      EitherT(
        F.uncancelable { nat =>
          val natT =
            new Poll[EitherT[F, E0, *]] {
              def apply[B](optfa: EitherT[F, E0, B]): EitherT[F, E0, B] =
                EitherT(nat(optfa.value))
            }
          body(natT).value
        }
      )

    def canceled: EitherT[F, E0, Unit] = EitherT.liftF(F.canceled)

    def onCancel[A](fa: EitherT[F, E0, A], fin: EitherT[F, E0, Unit]): EitherT[F, E0, A] =
      EitherT(F.onCancel(fa.value, fin.value.void))

    def forceR[A, B](fa: EitherT[F, E0, A])(fb: EitherT[F, E0, B]): EitherT[F, E0, B] =
      EitherT(
        F.forceR(fa.value)(fb.value)
      )

    def pure[A](a: A): EitherT[F, E0, A] = delegate.pure(a)

    def raiseError[A](e: E): EitherT[F, E0, A] = delegate.raiseError(e)

    def handleErrorWith[A](fa: EitherT[F, E0, A])(
        f: E => EitherT[F, E0, A]): EitherT[F, E0, A] =
      delegate.handleErrorWith(fa)(f)

    def flatMap[A, B](fa: EitherT[F, E0, A])(f: A => EitherT[F, E0, B]): EitherT[F, E0, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => EitherT[F, E0, Either[A, B]]): EitherT[F, E0, B] =
      delegate.tailRecM(a)(f)
  }

  private[kernel] trait IorTMonadCancel[F[_], L, E] extends MonadCancel[IorT[F, L, *], E] {

    implicit protected def F: MonadCancel[F, E]

    implicit protected def L: Semigroup[L]

    protected def delegate: MonadError[IorT[F, L, *], E] =
      IorT.catsDataMonadErrorFForIorT[F, L, E]

    def uncancelable[A](body: Poll[IorT[F, L, *]] => IorT[F, L, A]): IorT[F, L, A] =
      IorT(
        F.uncancelable { nat =>
          val natT = new Poll[IorT[F, L, *]] {
            def apply[B](optfa: IorT[F, L, B]): IorT[F, L, B] = IorT(nat(optfa.value))
          }
          body(natT).value
        }
      )

    def canceled: IorT[F, L, Unit] = IorT.liftF(F.canceled)

    def onCancel[A](fa: IorT[F, L, A], fin: IorT[F, L, Unit]): IorT[F, L, A] =
      IorT(F.onCancel(fa.value, fin.value.void))

    def forceR[A, B](fa: IorT[F, L, A])(fb: IorT[F, L, B]): IorT[F, L, B] =
      IorT(
        F.forceR(fa.value)(fb.value)
      )

    def pure[A](a: A): IorT[F, L, A] = delegate.pure(a)

    def raiseError[A](e: E): IorT[F, L, A] = delegate.raiseError(e)

    def handleErrorWith[A](fa: IorT[F, L, A])(f: E => IorT[F, L, A]): IorT[F, L, A] =
      delegate.handleErrorWith(fa)(f)

    def flatMap[A, B](fa: IorT[F, L, A])(f: A => IorT[F, L, B]): IorT[F, L, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => IorT[F, L, Either[A, B]]): IorT[F, L, B] =
      delegate.tailRecM(a)(f)
  }

  private[kernel] trait KleisliMonadCancel[F[_], R, E]
      extends MonadCancel[Kleisli[F, R, *], E] {

    implicit protected def F: MonadCancel[F, E]

    protected def delegate: MonadError[Kleisli[F, R, *], E] =
      Kleisli.catsDataMonadErrorForKleisli[F, R, E]

    def uncancelable[A](body: Poll[Kleisli[F, R, *]] => Kleisli[F, R, A]): Kleisli[F, R, A] =
      Kleisli { r =>
        F.uncancelable { nat =>
          val natT =
            new Poll[Kleisli[F, R, *]] {
              def apply[B](stfa: Kleisli[F, R, B]): Kleisli[F, R, B] =
                Kleisli { r => nat(stfa.run(r)) }
            }
          body(natT).run(r)
        }
      }

    def canceled: Kleisli[F, R, Unit] = Kleisli.liftF(F.canceled)

    def onCancel[A](fa: Kleisli[F, R, A], fin: Kleisli[F, R, Unit]): Kleisli[F, R, A] =
      Kleisli { r => F.onCancel(fa.run(r), fin.run(r)) }

    def forceR[A, B](fa: Kleisli[F, R, A])(fb: Kleisli[F, R, B]): Kleisli[F, R, B] =
      Kleisli(r => F.forceR(fa.run(r))(fb.run(r)))

    def pure[A](a: A): Kleisli[F, R, A] = delegate.pure(a)

    def raiseError[A](e: E): Kleisli[F, R, A] = delegate.raiseError(e)

    def handleErrorWith[A](fa: Kleisli[F, R, A])(f: E => Kleisli[F, R, A]): Kleisli[F, R, A] =
      delegate.handleErrorWith(fa)(f)

    def flatMap[A, B](fa: Kleisli[F, R, A])(f: A => Kleisli[F, R, B]): Kleisli[F, R, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => Kleisli[F, R, Either[A, B]]): Kleisli[F, R, B] =
      delegate.tailRecM(a)(f)
  }

  private[kernel] trait WriterTMonadCancel[F[_], L, E]
      extends MonadCancel[WriterT[F, L, *], E] {

    implicit protected def F: MonadCancel[F, E]

    implicit protected def L: Monoid[L]

    protected def delegate: MonadError[WriterT[F, L, *], E] =
      WriterT.catsDataMonadErrorForWriterT[F, L, E]

    def uncancelable[A](body: Poll[WriterT[F, L, *]] => WriterT[F, L, A]): WriterT[F, L, A] =
      WriterT(
        F.uncancelable { nat =>
          val natT =
            new Poll[WriterT[F, L, *]] {
              def apply[B](optfa: WriterT[F, L, B]): WriterT[F, L, B] = WriterT(nat(optfa.run))
            }
          body(natT).run
        }
      )

    def canceled: WriterT[F, L, Unit] = WriterT.liftF(F.canceled)

    //Note that this does not preserve the log from the finalizer
    def onCancel[A](fa: WriterT[F, L, A], fin: WriterT[F, L, Unit]): WriterT[F, L, A] =
      WriterT(F.onCancel(fa.run, fin.value.void))

    def forceR[A, B](fa: WriterT[F, L, A])(fb: WriterT[F, L, B]): WriterT[F, L, B] =
      WriterT(
        F.forceR(fa.run)(fb.run)
      )

    def pure[A](a: A): WriterT[F, L, A] = delegate.pure(a)

    def raiseError[A](e: E): WriterT[F, L, A] = delegate.raiseError(e)

    def handleErrorWith[A](fa: WriterT[F, L, A])(f: E => WriterT[F, L, A]): WriterT[F, L, A] =
      delegate.handleErrorWith(fa)(f)

    def flatMap[A, B](fa: WriterT[F, L, A])(f: A => WriterT[F, L, B]): WriterT[F, L, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => WriterT[F, L, Either[A, B]]): WriterT[F, L, B] =
      delegate.tailRecM(a)(f)
  }

  private[kernel] trait StateTMonadCancel[F[_], S, E] extends MonadCancel[StateT[F, S, *], E] {

    implicit protected def F: MonadCancel[F, E]

    protected def delegate: MonadError[StateT[F, S, *], E] =
      IndexedStateT.catsDataMonadErrorForIndexedStateT[F, S, E]

    def pure[A](x: A): StateT[F, S, A] =
      delegate.pure(x)

    def handleErrorWith[A](fa: StateT[F, S, A])(f: E => StateT[F, S, A]): StateT[F, S, A] =
      delegate.handleErrorWith(fa)(f)

    def raiseError[A](e: E): StateT[F, S, A] =
      delegate.raiseError(e)

    def flatMap[A, B](fa: StateT[F, S, A])(f: A => StateT[F, S, B]): StateT[F, S, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => StateT[F, S, Either[A, B]]): StateT[F, S, B] =
      delegate.tailRecM(a)(f)

    def canceled: StateT[F, S, Unit] =
      StateT.liftF(F.canceled)

    // discards state changes in fa
    def forceR[A, B](fa: StateT[F, S, A])(fb: StateT[F, S, B]): StateT[F, S, B] =
      StateT[F, S, B](s => F.forceR(fa.runA(s))(fb.run(s)))

    // discards state changes in fin, also fin cannot observe state changes in fa
    def onCancel[A](fa: StateT[F, S, A], fin: StateT[F, S, Unit]): StateT[F, S, A] =
      StateT[F, S, A](s => F.onCancel(fa.run(s), fin.runA(s)))

    def uncancelable[A](body: Poll[StateT[F, S, *]] => StateT[F, S, A]): StateT[F, S, A] =
      StateT[F, S, A] { s =>
        F uncancelable { poll =>
          val poll2 = new Poll[StateT[F, S, *]] {
            def apply[B](fb: StateT[F, S, B]) =
              StateT[F, S, B](s => poll(fb.run(s)))
          }

          body(poll2).run(s)
        }
      }
  }

  private[kernel] trait ReaderWriterStateTMonadCancel[F[_], E0, L, S, E]
      extends MonadCancel[ReaderWriterStateT[F, E0, L, S, *], E] {

    implicit protected def F: MonadCancel[F, E]

    implicit protected def L: Monoid[L]

    protected def delegate: MonadError[ReaderWriterStateT[F, E0, L, S, *], E] =
      IndexedReaderWriterStateT.catsDataMonadErrorForIRWST[F, E0, L, S, E]

    def pure[A](x: A): ReaderWriterStateT[F, E0, L, S, A] =
      delegate.pure(x)

    def handleErrorWith[A](fa: ReaderWriterStateT[F, E0, L, S, A])(
        f: E => ReaderWriterStateT[F, E0, L, S, A]): ReaderWriterStateT[F, E0, L, S, A] =
      delegate.handleErrorWith(fa)(f)

    def raiseError[A](e: E): ReaderWriterStateT[F, E0, L, S, A] =
      delegate.raiseError(e)

    def flatMap[A, B](fa: ReaderWriterStateT[F, E0, L, S, A])(
        f: A => ReaderWriterStateT[F, E0, L, S, B]): ReaderWriterStateT[F, E0, L, S, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => ReaderWriterStateT[F, E0, L, S, Either[A, B]])
        : ReaderWriterStateT[F, E0, L, S, B] =
      delegate.tailRecM(a)(f)

    def canceled: ReaderWriterStateT[F, E0, L, S, Unit] =
      ReaderWriterStateT.liftF(F.canceled)

    // discards state changes in fa
    def forceR[A, B](fa: ReaderWriterStateT[F, E0, L, S, A])(
        fb: ReaderWriterStateT[F, E0, L, S, B]): ReaderWriterStateT[F, E0, L, S, B] =
      ReaderWriterStateT[F, E0, L, S, B]((e, s) => F.forceR(fa.runA(e, s))(fb.run(e, s)))

    // discards state changes in fin, also fin cannot observe state changes in fa
    def onCancel[A](
        fa: ReaderWriterStateT[F, E0, L, S, A],
        fin: ReaderWriterStateT[F, E0, L, S, Unit]): ReaderWriterStateT[F, E0, L, S, A] =
      ReaderWriterStateT[F, E0, L, S, A]((e, s) => F.onCancel(fa.run(e, s), fin.runA(e, s)))

    def uncancelable[A](
        body: Poll[ReaderWriterStateT[F, E0, L, S, *]] => ReaderWriterStateT[F, E0, L, S, A])
        : ReaderWriterStateT[F, E0, L, S, A] =
      ReaderWriterStateT[F, E0, L, S, A] { (e, s) =>
        F uncancelable { poll =>
          val poll2 = new Poll[ReaderWriterStateT[F, E0, L, S, *]] {
            def apply[B](fb: ReaderWriterStateT[F, E0, L, S, B]) =
              ReaderWriterStateT[F, E0, L, S, B]((e, s) => poll(fb.run(e, s)))
          }

          body(poll2).run(e, s)
        }
      }
  }
}
