/*
 * Copyright 2020-2024 Typelevel
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

/**
 * A typeclass that characterizes monads which support safe cancelation, masking, and
 * finalization. [[MonadCancel]] extends the capabilities of [[cats.MonadError]], so an instance
 * of this typeclass must also provide a lawful instance for [[cats.MonadError]].
 *
 * ==Fibers==
 *
 * A fiber is a sequence of effects which are bound together by [[flatMap]]. The execution of a
 * fiber of an effect `F[E, A]` terminates with one of three outcomes, which are encoded by the
 * datatype [[Outcome]]:
 *
 *   1. [[Outcome.Succeeded]]: indicates success with a value of type `A`
 *   1. [[Outcome.Errored]]: indicates failure with a value of type `E`
 *   1. [[Outcome.Canceled]]: indicates abnormal termination
 *
 * Additionally, a fiber may never produce an outcome, in which case it is said to be
 * non-terminating.
 *
 * ==Cancelation==
 *
 * Cancelation refers to the act of requesting that the execution of a fiber be abnormally
 * terminated. [[MonadCancel]] exposes a means of self-cancelation, with which a fiber can
 * request that its own execution be terminated. Self-cancelation is achieved via
 * [[MonadCancel!.canceled canceled]].
 *
 * Cancelation is vaguely similar to the short-circuiting behavior introduced by
 * [[cats.MonadError]], but there are several key differences:
 *
 *   1. Cancelation is effective; if it is observed it must be respected, and it cannot be
 *      reversed. In contrast, [[cats.MonadError.handleError handleError]] exposes the ability
 *      to catch and recover from errors, and then proceed with normal execution.
 *   1. Cancelation can be masked via [[MonadCancel!.uncancelable]]. Masking is discussed in the
 *      next section.
 *   1. [[GenSpawn]] introduces external cancelation, another cancelation mechanism by which
 *      fibers can be canceled by external parties.
 *
 * ==Masking==
 *
 * Masking allows a fiber to suppress cancelation for a period of time, which is achieved via
 * [[MonadCancel!.uncancelable uncancelable]]. If a fiber is canceled while it is masked, the
 * cancelation is suppressed for as long as the fiber remains masked. Once the fiber reaches a
 * completely unmasked state, it responds to the cancelation.
 *
 * While a fiber is masked, it may optionally unmask by "polling", rendering itself cancelable
 * again.
 *
 * {{{
 *
 *   F.uncancelable { poll =>
 *     // can only observe cancelation within `fb`
 *     fa *> poll(fb) *> fc
 *   }
 *
 * }}}
 *
 * These semantics allow users to precisely mark what regions of code are cancelable within a
 * larger code block.
 *
 * ==Cancelation Boundaries==
 *
 * A boundary corresponds to an iteration of the internal runloop. In general they are
 * introduced by any of the combinators from the cats/cats effect hierarchy (`map`, `flatMap`,
 * `handleErrorWith`, `attempt`, etc).
 *
 * A cancelation boundary is a boundary where the cancelation status of a fiber may be checked
 * and hence cancelation observed. Note that in general you cannot guarantee that cancelation
 * will be observed at a given boundary. However, in the absence of masking it will be observed
 * eventually.
 *
 * With a small number of exceptions covered below, all boundaries are cancelable boundaries ie
 * cancelation may be observed before the invocation of any combinator.
 *
 * {{{
 *   fa
 *     .flatMap(f)
 *     .handleErrorWith(g)
 *     .map(h)
 * }}}
 *
 * If the fiber above is canceled then the cancelation status may be checked and the execution
 * terminated between any of the combinators.
 *
 * As noted above, there are some boundaries which are not cancelable boundaries:
 *
 *   1. Any boundary inside `uncancelable` and not inside `poll`. This is the definition of
 *      masking as above.
 *
 * {{{
 *   F.uncancelable( _ =>
 *     fa
 *       .flatMap(f)
 *       .handleErrorWith(g)
 *       .map(h)
 *   )
 * }}}
 *
 * None of the boundaries above are cancelation boundaries as cancelation is masked.
 *
 * 2. The boundary after `uncancelable`
 *
 * {{{
 *   F.uncancelable(poll => foo(poll)).flatMap(f)
 * }}}
 *
 * It is guaranteed that we will not observe cancelation after `uncancelable` and hence
 * `flatMap(f)` will be invoked. This is necessary for `uncancelable` to compose. Consider for
 * example `Resource#allocated`
 *
 * {{{
 *   def allocated[B >: A](implicit F: MonadCancel[F, Throwable]): F[(B, F[Unit])]
 * }}}
 *
 * which returns a tuple of the resource and a finalizer which needs to be invoked to clean-up
 * once the resource is no longer needed. The implementation of `allocated` can make sure it is
 * safe by appropriate use of `uncancelable`. However, if it were possible to observe
 * cancelation on the boundary directly after `allocated` then we would have a leak as the
 * caller would be unable to ensure that the finalizer is invoked. In other words, the safety of
 * `allocated` and the safety of `f` would not guarantee the safety of the composition
 * `allocated.flatMap(f)`.
 *
 * This does however mean that we violate the functor law that `fa.map(identity) <-> fa` as
 *
 * {{{
 *   F.uncancelable(_ => fa).onCancel(fin)  <-!-> F.uncancelable(_ => fa).map(identity).onCancel(fin)
 * }}}
 *
 * as cancelation may be observed before the `onCancel` on the RHS. The justification is that
 * cancelation is a hint rather than a mandate and so enshrining its behaviour in laws will
 * always be awkward. Given this, it is better to pick a semantic that allows safe composition
 * of regions.
 *
 * 3. The boundary after `poll`
 *
 * {{{
 *   F.uncancelable(poll => poll(fa).flatMap(f))
 * }}}
 *
 * If `fa` completes successfully then cancelation may not be observed after `poll` but before
 * `flatMap`. The reasoning is similar to above - if `fa` has successfully produced a value then
 * the caller should have the opportunity to observe the value and ensure finalizers are
 * in-place, etc.
 *
 * ==Finalization==
 *
 * Finalization refers to the act of running finalizers in response to a cancelation. Finalizers
 * are those effects whose evaluation is guaranteed in the event of cancelation. After a fiber
 * has completed finalization, it terminates with an outcome of `Canceled`.
 *
 * Finalizers can be registered to a fiber for the duration of some effect via
 * [[MonadCancel!.onCancel onCancel]]. If a fiber is canceled while running that effect, the
 * registered finalizer is guaranteed to be run before terminating.
 *
 * ==Bracket pattern==
 *
 * The aforementioned concepts work together to unlock a powerful pattern for safely interacting
 * with effectful lifecycles: the bracket pattern. This is analogous to the
 * try-with-resources/finally construct in Java.
 *
 * A lifecycle refers to a pair of actions, which are called the acquisition action and the
 * release action respectively. The relationship between these two actions is that if the former
 * completes successfully, then the latter is guaranteed to be run eventually, even in the
 * presence of errors and cancelation. While the lifecycle is active, other work can be
 * performed, but this invariant is always respected.
 *
 * The bracket pattern is an invaluable tool for safely handling resource lifecycles. Imagine an
 * application that opens network connections to a database server to do work. If a task in the
 * application is canceled while it holds an open database connection, the connection would
 * never be released or returned to a pool, causing a resource leak.
 *
 * To illustrate the compositional nature of [[MonadCancel]] and its combinators, the
 * implementation of [[MonadCancel!.bracket bracket]] is shown below:
 *
 * {{{
 *
 *   def bracket[A, B](acquire: F[A])(use: A => F[B])(release: A => F[Unit]): F[B] =
 *     uncancelable { poll =>
 *       flatMap(acquire) { a =>
 *         val finalized = onCancel(poll(use(a)), release(a).uncancelable)
 *         val handled = onError(finalized) { case e => void(attempt(release(a).uncancelable)) }
 *         flatMap(handled)(b => as(attempt(release(a).uncancelable), b))
 *       }
 *     }
 *
 * }}}
 *
 * See [[MonadCancel!.bracketCase bracketCase]] and [[MonadCancel!.bracketFull bracketFull]] for
 * other variants of the bracket pattern. If more specialized behavior is necessary, it is
 * recommended to use [[MonadCancel!.uncancelable uncancelable]] and
 * [[MonadCancel!.onCancel onCancel]] directly.
 */
trait MonadCancel[F[_], E] extends MonadError[F, E] {
  implicit private[this] def F: MonadError[F, E] = this

  /**
   * Indicates the default "root scope" semantics of the `F` in question. For types which do
   * ''not'' implement auto-cancelation, this value may be set to `CancelScope.Uncancelable`,
   * which behaves as if all values `F[A]` are wrapped in an implicit "outer" `uncancelable`
   * which cannot be polled. Most `IO`-like types will define this to be `Cancelable`.
   */
  def rootCancelScope: CancelScope

  /**
   * Analogous to [[productR]], but suppresses short-circuiting behavior except for cancelation.
   */
  def forceR[A, B](fa: F[A])(fb: F[B]): F[B]

  /**
   * Masks cancelation on the current fiber. The argument to `body` of type `Poll[F]` is a
   * natural transformation `F ~> F` that enables polling. Polling causes a fiber to unmask
   * within a masked region so that cancelation can be observed again.
   *
   * In the following example, cancelation can be observed only within `fb` and nowhere else:
   *
   * {{{
   *
   *   F.uncancelable { poll =>
   *     fa *> poll(fb) *> fc
   *   }
   *
   * }}}
   *
   * If a fiber is canceled while it is masked, the cancelation is suppressed for as long as the
   * fiber remains masked. Whenever the fiber is completely unmasked again, the cancelation will
   * be respected.
   *
   * Masks can also be stacked or nested within each other. If multiple masks are active, all
   * masks must be undone so that cancelation can be observed. In order to completely unmask
   * within a multi-masked region the poll corresponding to each mask must be applied to the
   * effect, outermost-first.
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
   *   1. Subsequent polls when applying the same poll more than once: `poll(poll(fa))` is
   *      equivalent to `poll(fa)`
   *   1. Applying a poll bound to one fiber within another fiber
   *
   * @param body
   *   A function which takes a [[Poll]] and returns the effect that we wish to make
   *   uncancelable.
   */
  def uncancelable[A](body: Poll[F] => F[A]): F[A]

  /**
   * An effect that requests self-cancelation on the current fiber.
   *
   * `canceled` has a return type of `F[Unit]` instead of `F[Nothing]` due to execution
   * continuing in a masked region. In the following example, the fiber requests
   * self-cancelation in a masked region, so cancelation is suppressed until the fiber is
   * completely unmasked. `fa` will run but `fb` will not. If `canceled` had a return type of
   * `F[Nothing]`, then it would not be possible to continue execution to `fa` (there would be
   * no `Nothing` value to pass to the `flatMap`).
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
   * Registers a finalizer that is invoked if cancelation is observed during the evaluation of
   * `fa`. If the evaluation of `fa` completes without encountering a cancelation, the finalizer
   * is unregistered before proceeding.
   *
   * Note that if `fa` is uncancelable (e.g. created via [[uncancelable]]) then `fin` won't be
   * fired.
   *
   * {{{
   *   F.onCancel(F.uncancelable(_ => F.canceled), fin) <-> F.unit
   * }}}
   *
   * During finalization, all actively registered finalizers are run exactly once. The order by
   * which finalizers are run is dictated by nesting: innermost finalizers are run before
   * outermost finalizers. For example, in the following program, the finalizer `f1` is run
   * before the finalizer `f2`:
   *
   * {{{
   *   F.onCancel(F.onCancel(F.canceled, f1), f2)
   * }}}
   *
   * If a finalizer throws an error during evaluation, the error is suppressed, and
   * implementations may choose to report it via a side channel. Finalizers are always
   * uncancelable, so cannot otherwise be interrupted.
   *
   * @param fa
   *   The effect that is evaluated after `fin` is registered.
   * @param fin
   *   The finalizer to register before evaluating `fa`.
   */
  def onCancel[A](fa: F[A], fin: F[Unit]): F[A]

  /**
   * Specifies an effect that is always invoked after evaluation of `fa` completes, regardless
   * of the outcome.
   *
   * This function can be thought of as a combination of [[cats.Monad!.flatTap flatTap]],
   * [[cats.MonadError!.onError onError]], and [[MonadCancel!.onCancel onCancel]].
   *
   * @param fa
   *   The effect that is run after `fin` is registered.
   * @param fin
   *   The effect to run in the event of a cancelation or error.
   *
   * @see
   *   [[guaranteeCase]] for a more powerful variant
   *
   * @see
   *   [[Outcome]] for the various outcomes of evaluation
   */
  def guarantee[A](fa: F[A], fin: F[Unit]): F[A] =
    guaranteeCase(fa)(_ => fin)

  /**
   * Specifies an effect that is always invoked after evaluation of `fa` completes, but depends
   * on the outcome.
   *
   * This function can be thought of as a combination of [[cats.Monad!.flatTap flatTap]],
   * [[cats.MonadError!.onError onError]], and [[MonadCancel!.onCancel onCancel]].
   *
   * @param fa
   *   The effect that is run after `fin` is registered.
   * @param fin
   *   A function that returns the effect to run based on the outcome.
   *
   * @see
   *   [[bracketCase]] for a more powerful variant
   *
   * @see
   *   [[Outcome]] for the various outcomes of evaluation
   */
  def guaranteeCase[A](fa: F[A])(fin: Outcome[F, E, A] => F[Unit]): F[A] =
    uncancelable { poll =>
      val finalized = onCancel(poll(fa), fin(Outcome.canceled))
      val handled = onError(finalized) {
        case e => handleError(fin(Outcome.errored(e)))(_ => ())
      }
      flatTap(handled)(a => fin(Outcome.succeeded(pure(a))))
    }

  /**
   * A pattern for safely interacting with effectful lifecycles.
   *
   * If `acquire` completes successfully, `use` is called. If `use` succeeds, fails, or is
   * canceled, `release` is guaranteed to be called exactly once.
   *
   * `acquire` is uncancelable. `release` is uncancelable. `use` is cancelable by default, but
   * can be masked.
   *
   * @param acquire
   *   the lifecycle acquisition action
   * @param use
   *   the effect to which the lifecycle is scoped, whose result is the return value of this
   *   function
   * @param release
   *   the lifecycle release action
   *
   * @see
   *   [[bracketCase]] for a more powerful variant
   *
   * @see
   *   [[Resource]] for a composable datatype encoding of effectful lifecycles
   */
  def bracket[A, B](acquire: F[A])(use: A => F[B])(release: A => F[Unit]): F[B] =
    bracketCase(acquire)(use)((a, _) => release(a))

  /**
   * A pattern for safely interacting with effectful lifecycles.
   *
   * If `acquire` completes successfully, `use` is called. If `use` succeeds, fails, or is
   * canceled, `release` is guaranteed to be called exactly once.
   *
   * `acquire` is uncancelable. `release` is uncancelable. `use` is cancelable by default, but
   * can be masked.
   *
   * @param acquire
   *   the lifecycle acquisition action
   * @param use
   *   the effect to which the lifecycle is scoped, whose result is the return value of this
   *   function
   * @param release
   *   the lifecycle release action which depends on the outcome of `use`
   *
   * @see
   *   [[bracketFull]] for a more powerful variant
   *
   * @see
   *   [[Resource]] for a composable datatype encoding of effectful lifecycles
   */
  def bracketCase[A, B](acquire: F[A])(use: A => F[B])(
      release: (A, Outcome[F, E, B]) => F[Unit]): F[B] =
    bracketFull(_ => acquire)(use)(release)

  /**
   * A pattern for safely interacting with effectful lifecycles.
   *
   * If `acquire` completes successfully, `use` is called. If `use` succeeds, fails, or is
   * canceled, `release` is guaranteed to be called exactly once.
   *
   * If `use` succeeds the returned value `B` is returned. If `use` returns an exception, the
   * exception is returned.
   *
   * `acquire` is uncancelable by default, but can be unmasked. `release` is uncancelable. `use`
   * is cancelable by default, but can be masked.
   *
   * @param acquire
   *   the lifecycle acquisition action which can be canceled
   * @param use
   *   the effect to which the lifecycle is scoped, whose result is the return value of this
   *   function
   * @param release
   *   the lifecycle release action which depends on the outcome of `use`
   */
  def bracketFull[A, B](acquire: Poll[F] => F[A])(use: A => F[B])(
      release: (A, Outcome[F, E, B]) => F[Unit]): F[B] =
    uncancelable { poll =>
      acquire(poll).flatMap { a =>
        // we need to lazily evaluate `use` so that uncaught exceptions are caught within the effect
        // runtime, otherwise we'll throw here and the error handler will never be registered
        guaranteeCase(poll(unit >> use(a)))(release(a, _))
      }
    }
}

object MonadCancel {

  def apply[F[_], E](implicit F: MonadCancel[F, E]): F.type = F
  def apply[F[_]](implicit F: MonadCancel[F, _], d: DummyImplicit): F.type = F

  implicit def monadCancelForOptionT[F[_], E](
      implicit F0: MonadCancel[F, E]): MonadCancel[OptionT[F, *], E] =
    F0 match {
      case async: Async[F @unchecked] =>
        Async.asyncForOptionT[F](async)
      case sync: Sync[F @unchecked] =>
        Sync.instantiateSyncForOptionT[F](sync)
      case temporal: GenTemporal[F @unchecked, E @unchecked] =>
        GenTemporal.instantiateGenTemporalForOptionT[F, E](temporal)
      case concurrent: GenConcurrent[F @unchecked, E @unchecked] =>
        GenConcurrent.instantiateGenConcurrentForOptionT[F, E](concurrent)
      case spawn: GenSpawn[F @unchecked, E @unchecked] =>
        GenSpawn.instantiateGenSpawnForOptionT[F, E](spawn)
      case cancel =>
        new OptionTMonadCancel[F, E] {
          def rootCancelScope = F0.rootCancelScope
          override implicit protected def F: MonadCancel[F, E] = cancel
        }
    }

  implicit def monadCancelForEitherT[F[_], E0, E](
      implicit F0: MonadCancel[F, E]): MonadCancel[EitherT[F, E0, *], E] =
    F0 match {
      case async: Async[F @unchecked] =>
        Async.asyncForEitherT[F, E0](async)
      case sync: Sync[F @unchecked] =>
        Sync.instantiateSyncForEitherT[F, E0](sync)
      case temporal: GenTemporal[F @unchecked, E @unchecked] =>
        GenTemporal.instantiateGenTemporalForEitherT[F, E0, E](temporal)
      case concurrent: GenConcurrent[F @unchecked, E @unchecked] =>
        GenConcurrent.instantiateGenConcurrentForEitherT[F, E0, E](concurrent)
      case spawn: GenSpawn[F @unchecked, E @unchecked] =>
        GenSpawn.instantiateGenSpawnForEitherT[F, E0, E](spawn)
      case cancel =>
        new EitherTMonadCancel[F, E0, E] {
          def rootCancelScope = F0.rootCancelScope
          override implicit protected def F: MonadCancel[F, E] = cancel
        }
    }

  implicit def monadCancelForKleisli[F[_], R, E](
      implicit F0: MonadCancel[F, E]): MonadCancel[Kleisli[F, R, *], E] =
    F0 match {
      case async: Async[F @unchecked] =>
        Async.asyncForKleisli[F, R](async)
      case sync: Sync[F @unchecked] =>
        Sync.instantiateSyncForKleisli[F, R](sync)
      case temporal: GenTemporal[F @unchecked, E @unchecked] =>
        GenTemporal.instantiateGenTemporalForKleisli[F, R, E](temporal)
      case concurrent: GenConcurrent[F @unchecked, E @unchecked] =>
        GenConcurrent.instantiateGenConcurrentForKleisli[F, R, E](concurrent)
      case spawn: GenSpawn[F @unchecked, E @unchecked] =>
        GenSpawn.instantiateGenSpawnForKleisli[F, R, E](spawn)
      case cancel =>
        new KleisliMonadCancel[F, R, E] {
          def rootCancelScope = F0.rootCancelScope
          override implicit protected def F: MonadCancel[F, E] = cancel
        }
    }

  implicit def monadCancelForIorT[F[_], L, E](
      implicit F0: MonadCancel[F, E],
      L0: Semigroup[L]): MonadCancel[IorT[F, L, *], E] =
    F0 match {
      case async: Async[F @unchecked] =>
        Async.asyncForIorT[F, L](async, L0)
      case sync: Sync[F @unchecked] =>
        Sync.instantiateSyncForIorT[F, L](sync)
      case temporal: GenTemporal[F @unchecked, E @unchecked] =>
        GenTemporal.instantiateGenTemporalForIorT[F, L, E](temporal)
      case concurrent: GenConcurrent[F @unchecked, E @unchecked] =>
        GenConcurrent.instantiateGenConcurrentForIorT[F, L, E](concurrent)
      case spawn: GenSpawn[F @unchecked, E @unchecked] =>
        GenSpawn.instantiateGenSpawnForIorT[F, L, E](spawn)
      case cancel =>
        new IorTMonadCancel[F, L, E] {
          def rootCancelScope = F0.rootCancelScope
          override implicit protected def F: MonadCancel[F, E] = cancel
          override implicit protected def L: Semigroup[L] = L0
        }
    }

  implicit def monadCancelForWriterT[F[_], L, E](
      implicit F0: MonadCancel[F, E],
      L0: Monoid[L]): MonadCancel[WriterT[F, L, *], E] =
    F0 match {
      case async: Async[F @unchecked] =>
        Async.asyncForWriterT[F, L](async, L0)
      case sync: Sync[F @unchecked] =>
        Sync.instantiateSyncForWriterT[F, L](sync)
      case temporal: GenTemporal[F @unchecked, E @unchecked] =>
        GenTemporal.instantiateGenTemporalForWriterT[F, L, E](temporal)
      case concurrent: GenConcurrent[F @unchecked, E @unchecked] =>
        GenConcurrent.instantiateGenConcurrentForWriterT[F, L, E](concurrent)
      case spawn: GenSpawn[F @unchecked, E @unchecked] =>
        GenSpawn.instantiateGenSpawnForWriterT[F, L, E](spawn)
      case cancel =>
        new WriterTMonadCancel[F, L, E] {
          def rootCancelScope = F0.rootCancelScope
          override implicit protected def F: MonadCancel[F, E] = cancel
          override implicit protected def L: Monoid[L] = L0
        }
    }

  implicit def monadCancelForStateT[F[_], S, E](
      implicit F0: MonadCancel[F, E]): MonadCancel[StateT[F, S, *], E] =
    F0 match {
      case sync: Sync[F @unchecked] =>
        Sync.syncForStateT[F, S](sync)
      case cancel =>
        new StateTMonadCancel[F, S, E] {
          def rootCancelScope = F0.rootCancelScope
          override implicit protected def F: MonadCancel[F, E] = cancel
        }
    }

  implicit def monadCancelForReaderWriterStateT[F[_], E0, L, S, E](
      implicit F0: MonadCancel[F, E],
      L0: Monoid[L]): MonadCancel[ReaderWriterStateT[F, E0, L, S, *], E] =
    F0 match {
      case sync: Sync[F @unchecked] =>
        Sync.syncForReaderWriterStateT[F, E0, L, S](sync, L0)
      case cancel =>
        new ReaderWriterStateTMonadCancel[F, E0, L, S, E] {
          def rootCancelScope = F0.rootCancelScope
          override implicit protected def F: MonadCancel[F, E] = cancel
          override implicit protected def L: Monoid[L] = L0
        }
    }

  trait Uncancelable[F[_], E] { this: MonadCancel[F, E] =>

    private[this] val IdPoll = new Poll[F] {
      def apply[A](fa: F[A]) = fa
    }

    def rootCancelScope: CancelScope = CancelScope.Uncancelable

    def canceled: F[Unit] = unit

    def onCancel[A](fa: F[A], fin: F[Unit]): F[A] = {
      val _ = fin
      fa
    }

    def uncancelable[A](body: Poll[F] => F[A]): F[A] =
      body(IdPoll)
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

    override def guaranteeCase[A](fa: OptionT[F, A])(
        fin: Outcome[OptionT[F, *], E, A] => OptionT[F, Unit]): OptionT[F, A] =
      OptionT {
        F.guaranteeCase(fa.value) {
          case Outcome.Succeeded(fa) => fin(Outcome.succeeded(OptionT(fa))).value.void
          case Outcome.Errored(e) => fin(Outcome.errored(e)).value.void.handleError(_ => ())
          case Outcome.Canceled() => fin(Outcome.canceled).value.void
        }
      }

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

    override def guaranteeCase[A](fa: EitherT[F, E0, A])(
        fin: Outcome[EitherT[F, E0, *], E, A] => EitherT[F, E0, Unit]): EitherT[F, E0, A] =
      EitherT {
        F.guaranteeCase(fa.value) {
          case Outcome.Succeeded(fa) => fin(Outcome.succeeded(EitherT(fa))).value.void
          case Outcome.Errored(e) => fin(Outcome.errored(e)).value.void.handleError(_ => ())
          case Outcome.Canceled() => fin(Outcome.canceled).value.void
        }
      }

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

    override def guaranteeCase[A](fa: IorT[F, L, A])(
        fin: Outcome[IorT[F, L, *], E, A] => IorT[F, L, Unit]): IorT[F, L, A] =
      IorT {
        F.guaranteeCase(fa.value) {
          case Outcome.Succeeded(fa) => fin(Outcome.succeeded(IorT(fa))).value.void
          case Outcome.Errored(e) => fin(Outcome.errored(e)).value.void.handleError(_ => ())
          case Outcome.Canceled() => fin(Outcome.canceled).value.void
        }
      }

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

    override def guaranteeCase[A](fa: Kleisli[F, R, A])(
        fin: Outcome[Kleisli[F, R, *], E, A] => Kleisli[F, R, Unit]): Kleisli[F, R, A] =
      Kleisli { r =>
        F.guaranteeCase(fa.run(r)) {
          case Outcome.Succeeded(fa) => fin(Outcome.succeeded(Kleisli.liftF(fa))).run(r)
          case Outcome.Errored(e) => fin(Outcome.errored(e)).run(r).handleError(_ => ())
          case Outcome.Canceled() => fin(Outcome.canceled).run(r)
        }
      }

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

    // Note that this does not preserve the log from the finalizer
    def onCancel[A](fa: WriterT[F, L, A], fin: WriterT[F, L, Unit]): WriterT[F, L, A] =
      WriterT(F.onCancel(fa.run, fin.value.void))

    def forceR[A, B](fa: WriterT[F, L, A])(fb: WriterT[F, L, B]): WriterT[F, L, B] =
      WriterT(
        F.forceR(fa.run)(fb.run)
      )

    override def guaranteeCase[A](fa: WriterT[F, L, A])(
        fin: Outcome[WriterT[F, L, *], E, A] => WriterT[F, L, Unit]): WriterT[F, L, A] =
      WriterT {
        F.guaranteeCase(fa.run) {
          case Outcome.Succeeded(fa) => fin(Outcome.succeeded(WriterT(fa))).run.void
          case Outcome.Errored(e) => fin(Outcome.errored(e)).run.void.handleError(_ => ())
          case Outcome.Canceled() => fin(Outcome.canceled).run.void
        }
      }

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

    override def guaranteeCase[A](fa: StateT[F, S, A])(
        fin: Outcome[StateT[F, S, *], E, A] => StateT[F, S, Unit]): StateT[F, S, A] =
      StateT { s =>
        F.guaranteeCase(fa.run(s)) {
          case Outcome.Succeeded(fa) => fin(Outcome.succeeded(StateT(_ => fa))).run(s).void
          case Outcome.Errored(e) => fin(Outcome.errored(e)).run(s).void.handleError(_ => ())
          case Outcome.Canceled() => fin(Outcome.canceled).run(s).void
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

    override def guaranteeCase[A](fa: ReaderWriterStateT[F, E0, L, S, A])(
        fin: Outcome[ReaderWriterStateT[F, E0, L, S, *], E, A] => ReaderWriterStateT[
          F,
          E0,
          L,
          S,
          Unit]): ReaderWriterStateT[F, E0, L, S, A] =
      ReaderWriterStateT { (e0, s) =>
        F.guaranteeCase(fa.run(e0, s)) {
          case Outcome.Succeeded(fa) =>
            fin(Outcome.succeeded(ReaderWriterStateT((_, _) => fa))).run(e0, s).void
          case Outcome.Errored(e) =>
            fin(Outcome.errored(e)).run(e0, s).void.handleError(_ => ())
          case Outcome.Canceled() => fin(Outcome.canceled).run(e0, s).void
        }
      }
  }
}
