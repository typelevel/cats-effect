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

import cats.data._

/**
 * An extension of `MonadError` exposing the `bracket` operation,
 * a generalized abstracted pattern of safe resource acquisition and
 * release in the face of errors or interruption.
 *
 * @define acquireParam is an action that "acquires" some expensive
 *         resource, that needs to be used and then discarded
 *
 * @define useParam is the action that uses the newly allocated
 *         resource and that will provide the final result
 */
trait Bracket[F[_], E] extends MonadError[F, E] {
  /**
   * A generalized version of [[bracket]] which uses [[ExitCase]]
   * to distinguish between different exit cases when releasing
   * the acquired resource.
   *
   * @param acquire $acquireParam
   * @param use $useParam
   * @param release is the action that's supposed to release the
   *        allocated resource after `use` is done, by observing
   *        and acting on its exit condition
   */
  def bracketCase[A, B](acquire: F[A])(use: A => F[B])
    (release: (A, ExitCase[E]) => F[Unit]): F[B]

  /**
   * Operation meant for specifying tasks with safe resource
   * acquisition and release in the face of errors and interruption.
   *
   * This operation provides the equivalent of `try/catch/finally`
   * statements in mainstream imperative languages for resource
   * acquisition and release.
   *
   * @param acquire $acquireParam
   * @param use $useParam
   * @param release is the action that's supposed to release the
   *        allocated resource after `use` is done, irregardless of
   *        its exit condition
   */
  def bracket[A, B](acquire: F[A])(use: A => F[B])
    (release: A => F[Unit]): F[B] =
      bracketCase(acquire)(use)((a, _) => release(a))

}

/**
 * Type for signaling the exit condition of an effectful
 * computation, that may either succeed, fail with an error or
 * get canceled.
 *
 * The types of exit signals are:
 *
 *  - [[ExitCase$.Completed Completed]]: for successful
 *    completion (from the type of view of this `MonadError`)
 *  - [[ExitCase$.Error Error]]: for termination in failure
 *    (via `MonadError[F, E]`)
 *  - [[ExitCase$.Canceled Canceled]]: for abortion
 */
sealed abstract class ExitCase[+E] extends Product with Serializable

object ExitCase {
  /**
   * An [[ExitCase]] that signals successful completion.
   *
   * Note that "successful" is from the type of view of the
   * `MonadError` type that's implementing [[Bracket]].
   * When combining such a type with `EitherT` or `OptionT` for
   * example, this exit condition might not signal a successful
   * outcome for the user, but it does for the purposes of the
   * `bracket` operation.
   */
  final case object Completed extends ExitCase[Nothing]

  /**
   * An [[ExitCase]] signaling completion in failure.
   */
  final case class Error[+E](e: E) extends ExitCase[E]

  /**
   * An [[ExitCase]] signaling that the action was aborted.
   *
   * As an example this can happen when we have a cancelable data type,
   * like [[IO]] and the task yielded by `bracket` gets canceled
   * when it's at its `use` phase.
   *
   * Thus [[Bracket]] allows you to observe interruption conditions
   * and act on them.
   */
  final case class Canceled[+E](e: Option[E]) extends ExitCase[E]

  /**
   * Parametrized alias for the [[Completed]] data constructor.
   */
  def complete[E]: ExitCase[E] = Completed

  /**
   * Alias for the [[Error]] data constructor.
   */
  def error[E](e: E): ExitCase[E] = Error[E](e)

  /**
   * Alias for `Canceled(None)`.
   */
  def canceled[E]: ExitCase[E] = Canceled(None)

  /**
   * Alias for `Canceled(Some(e))`.
   */
  def canceledWith[E](e: E): ExitCase[E] = Canceled(Some(e))

  /**
   * Converts from Scala's `Either`, which is often the result of
   * `MonadError`'s `attempt` operation, into an [[ExitCase]].
   */
  def attempt[E, A](value: Either[E, A]): ExitCase[E] =
    value match {
      case Left(e) => ExitCase.error(e)
      case Right(_) => ExitCase.complete
    }
}

object Bracket {

  def apply[F[_], E](implicit ev: Bracket[F, E]): Bracket[F, E] = ev

  /**
   * [[Bracket]] instance built for `cats.data.Kleisli` values initialized
   * with any `F` data type that also implements `Bracket`.
   */
  implicit def catsKleisliBracket[F[_], R, E](implicit ev: Bracket[F, E]): Bracket[Kleisli[F, R, ?], E] =
    new KleisliBracket[F, R, E] { def F = ev }

  /**
    * [[Bracket]] instance built for `cats.data.StateT` values initialized
    * with any `F` data type that also implements `Bracket`
    */
  implicit def catsStateTBracket[F[_], S, E](implicit ev: Bracket[F, E]): Bracket[StateT[F, S, ?], E] =
    new StateTBracket[F, S, E] { def F = ev }

  /**
    * [[Bracket]] instance built for `cats.data.OptionT` values initialized
    * with any `F` data type that also has a `Bracket` instance
    */

  implicit def catsOptionTBracket[F[_], E](implicit ev: Bracket[F, E]): Bracket[OptionT[F, ?], E] =
    new OptionTBracket[F, E] { def F = ev}

  private[effect] trait StateTBracket[F[_], S, E] extends Bracket[StateT[F, S, ?], E] {

    protected implicit def F: Bracket[F, E]

    private[this] final val stateTMonadError: MonadError[StateT[F, S, ?], E] =
      IndexedStateT.catsDataMonadErrorForIndexedStateT

    def pure[A](x: A): StateT[F, S, A] =
      StateT.pure(x)

    def raiseError[A](e: E): StateT[F, S, A] =
      stateTMonadError.raiseError(e)

    def handleErrorWith[A](fa: StateT[F, S, A])(f: E => StateT[F, S, A]): StateT[F, S, A] =
      stateTMonadError.handleErrorWith(fa)(f)

    def flatMap[A, B](fa: StateT[F, S, A])(f: A => StateT[F, S, B]): StateT[F, S, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => StateT[F, S, Either[A, B]]): StateT[F, S, B] =
      stateTMonadError.tailRecM(a)(f)

    def bracketCase[A, B](acquire: StateT[F, S, A])(use:  A => StateT[F, S, B])
                         (release: (A, ExitCase[E]) => StateT[F, S, Unit]): StateT[F, S, B] = {
      StateT { startS =>
        F.bracketCase(acquire.run(startS)) { case (s, a) =>
          use(a).run(s)
        } { case ((s, a), br) =>
            F.void(release(a, br).run(s))
        }
      }
    }
  }

  private[effect] trait OptionTBracket[F[_], E] extends Bracket[OptionT[F, ?], E]{

    protected implicit def F: Bracket[F, E]

    private[this] final val optionTMonadError: MonadError[OptionT[F, ?], E] =
      OptionT.catsDataMonadErrorForOptionT

    def pure[A](x: A): OptionT[F, A] = OptionT.pure(x)

    def raiseError[A](e: E): OptionT[F, A] =
      optionTMonadError.raiseError(e)

    def handleErrorWith[A](fa: OptionT[F, A])(f: E => OptionT[F, A]): OptionT[F, A] =
      optionTMonadError.handleErrorWith(fa)(f)

    def flatMap[A, B](fa: OptionT[F, A])(f: A => OptionT[F, B]): OptionT[F, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => OptionT[F, Either[A, B]]): OptionT[F, B] =
      optionTMonadError.tailRecM(a)(f)

    def bracketCase[A, B](acquire: OptionT[F, A])(use: A => OptionT[F, B])
                         (release: (A, ExitCase[E]) => OptionT[F, Unit]): OptionT[F, B] = {
      OptionT {
        F.bracketCase(acquire.value) {
          case Some(a) => use(a).value
          case _ => F.pure[Option[B]](None)
        } {
          case (Some(a), br) =>
            F.void(release(a, br).value)
          case _ =>
            F.unit
        }
      }
    }
  }

  private[effect] abstract class KleisliBracket[F[_], R, E] extends Bracket[Kleisli[F, R, ?], E] {

    protected implicit def F: Bracket[F, E]

    // NB: preferably we'd inherit things from `cats.data.KleisliApplicativeError`,
    // but we can't, because it's `private[data]`, so we have to delegate.
    private[this] final val kleisliMonadError: MonadError[Kleisli[F, R, ?], E] =
      Kleisli.catsDataMonadErrorForKleisli

    def pure[A](x: A): Kleisli[F, R, A] =
      kleisliMonadError.pure(x)

    def handleErrorWith[A](fa: Kleisli[F, R, A])(f: E => Kleisli[F, R, A]): Kleisli[F, R, A] =
      kleisliMonadError.handleErrorWith(fa)(f)

    def raiseError[A](e: E): Kleisli[F, R, A] =
      kleisliMonadError.raiseError(e)

    def flatMap[A, B](fa: Kleisli[F, R, A])(f: A => Kleisli[F, R, B]): Kleisli[F, R, B] =
      kleisliMonadError.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => Kleisli[F, R, Either[A, B]]): Kleisli[F, R, B] =
      kleisliMonadError.tailRecM(a)(f)

    def bracketCase[A, B](acquire: Kleisli[F, R, A])
      (use: A => Kleisli[F, R, B])
      (release: (A, ExitCase[E]) => Kleisli[F, R, Unit]): Kleisli[F, R, B] = {

      Kleisli { r =>
        F.bracketCase(acquire.run(r))(a => use(a).run(r)) { (a, br) =>
          release(a, br).run(r)
        }
      }
    }
  }
}
