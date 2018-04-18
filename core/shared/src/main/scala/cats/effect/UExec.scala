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

import cats.{Eval, Monad, Now}
import cats.effect.internals.{ExecNewtype, NonFatal}
import cats.kernel.{Monoid, Semigroup}

object UExecImpl extends UExecInstances with ExecNewtype {

  /**
    * Construct a non-effectful value of `UExec`.
    * This should NOT be used with side-effects as evaluation is eager in this case.
    */
  def pure[A](a: A): UExec[A] = create(Eval.now(a))

  /** Alias for `UExec.pure(())`. */
  val unit: UExec[Unit] = pure(())

  /**
    * Suspends a synchronous side effect in `UExec`.
    * Warning: It does not, however catch any Exceptions.
    * Therefore it is recommended to be very conservative with this function
    * and only use it when you are 100% sure the body never throws an Exception.
    * If you're not sure if you should be using this method or not, use `delayCatch`
    */
  def delayNoCatch[A](thunk: => A): UExec[A] = create(Eval.always(thunk))

  /**
    * Suspends a synchronous side effect in `UExec` and catches non-fatal exceptions inside `Either`.
    */
  def apply[A](thunk: => A): UExec[Either[Throwable, A]] =
    create(Eval.always(try {
      Right(thunk)
    } catch {
      case NonFatal(t) => Left(t)
    }))

  /**
    * Lifts an `Eval` into `UExec`.
    *
    * This function will preserve the evaluation semantics of any
    * actions that are lifted into the pure `UExec`.  Eager `Eval`
    * instances will be converted into thunk-less `UExec` (i.e. eager
    * `UExec`), while lazy eval and memoized will be executed as such.
    */
  def eval[A](fa: Eval[A]): UExec[A] = fa match {
    case Now(a) => pure(a)
    case notNow => delayNoCatch(notNow.value)
  }

  implicit def catsEvalEffOps[A](value: UExec[A]): UExecOps[A] =
    new UExecOps(value)
}

sealed class UExecOps[A](val value: UExec[A]) {

  /**
    * Produces the result by running the encapsulated effects as impure
    * side effects.
    *
    * As the name says, this is an UNSAFE function as it is impure and
    * performs side effects. You should ideally only call this function
    * *once*, at the very end of your program.
    */
  def unsafeRun: A =
    UExecImpl.unwrap(value).value

  /**
    * Convert this `UExec` to an `IO`, preserving purity and allowing
    * for interop with asynchronous computations.
    */
  def toIO: IO[A] =
    to[IO]

  /**
    * Convert this `UExec` to another effect type that implements the [[Sync]] type class.
    */
  def to[F[_]: Sync]: F[A] =
    Sync[F].delay(unsafeRun)
}

private[effect] sealed abstract class UExecInstances extends UExecInstances0 {

  implicit val catsUExecMonad: Monad[UExec] = new Monad[UExec] {
    def flatMap[A, B](fa: UExec[A])(f: A => UExec[B]): UExec[B] =
      UExecImpl.create(UExecImpl.unwrap(fa).flatMap(f andThen(UExecImpl.unwrap)))

    def tailRecM[A, B](a: A)(f: A => UExec[Either[A, B]]): UExec[B] =
      UExecImpl.create(Monad[Eval].tailRecM(a)(f andThen(UExecImpl.unwrap)))

    def pure[A](x: A): UExec[A] =
      UExecImpl.create(Eval.now(x))
  }

  implicit def catsUExecMonoid[A: Monoid]: Monoid[UExec[A]] = new UExecSemigroup[A] with Monoid[UExec[A]] {
    val empty: UExec[A] = UExec.pure(Monoid[A].empty)
  }
}

private[effect] sealed abstract class UExecInstances0 {
  implicit def catsExecSemigroup[A: Semigroup]: Semigroup[UExec[A]] = new UExecSemigroup[A]
}


private[effect] class UExecSemigroup[A: Semigroup] extends Semigroup[UExec[A]] {
  def combine(x: UExec[A], y: UExec[A]): UExec[A] =
    UExec.create(UExecImpl.unwrap(x).flatMap(a => UExecImpl.unwrap(y).map(Semigroup[A].combine(a, _))))
}
