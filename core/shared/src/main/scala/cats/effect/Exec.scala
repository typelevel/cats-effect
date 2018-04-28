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

object Exec extends ExecInstances with ExecNewtype {

  /**
    * Construct a non-effectful value of `Exec`.
    * This should NOT be used with side-effects as evaluation is eager in this case.
    */
  def pure[A](a: A): Exec[A] = create(Eval.now(a))

  /** Alias for `Exec.pure(())`. */
  val unit: Exec[Unit] = pure(())

  /**
    * Suspends a synchronous side effect in `Exec`.
    * Warning: It does not, however catch any Exceptions.
    * Therefore it is recommended to be very conservative with this function
    * and only use it when you are 100% sure the body never throws an Exception.
    * If you're not sure if you should be using this method or not, use `delayCatch`
    */
  def delayNoCatch[A](thunk: => A): Exec[A] = create(Eval.always(thunk))

  /**
   * Suspends a synchronous side effect which produces an `Exec` in `Exec`.
   *
   * This is useful for trampolining (i.e. when the side effect is
   * conceptually the allocation of a stack frame).
   */
  def suspend[A](thunk: => Exec[A]) = create(Eval.defer(unwrap(thunk)))

  /**
    * Suspends a synchronous side effect in `Exec` and catches non-fatal exceptions inside `Either`.
    */
  def apply[A](thunk: => A): Exec[Either[Throwable, A]] =
    create(Eval.always(try {
      Right(thunk)
    } catch {
      case NonFatal(t) => Left(t)
    }))

  /**
    * Lifts an `Eval` into `Exec`.
    *
    * This function will preserve the evaluation semantics of any
    * actions that are lifted into the pure `Exec`.  Eager `Eval`
    * instances will be converted into thunk-less `Exec` (i.e. eager
    * `Exec`), while lazy eval and memoized will be executed as such.
    */
  def eval[A](fa: Eval[A]): Exec[A] = fa match {
    case Now(a) => pure(a)
    case notNow => delayNoCatch(notNow.value)
  }

  implicit class catsEvalEffOps[A](val value: Exec[A]) extends AnyVal {

    /**
     * Produces the result by running the encapsulated effects as impure
     * side effects.
     *
     * As the name says, this is an UNSAFE function as it is impure and
     * performs side effects. You should ideally only call this function
     * *once*, at the very end of your program.
     */
    def unsafeRun: A =
      Exec.unwrap(value).value

    /**
     * Convert this `Exec` to an `IO`, preserving purity and allowing
     * for interop with asynchronous computations.
     */
    def toIO: IO[A] =
      to[IO]

    /**
     * Convert this `Exec` to another effect type that implements the [[Sync]] type class.
     */
    def to[F[_]](implicit F: Sync[F]): F[A] = unwrap(value) match {
      case Now(a) => F.pure(a)
      case notNow => F.delay(notNow.value)
    }
  }
}

private[effect] sealed abstract class ExecInstances extends UExecInstances0 {

  implicit val catsUExecMonad: Monad[Exec] = new Monad[Exec] {
    def flatMap[A, B](fa: Exec[A])(f: A => Exec[B]): Exec[B] =
      Exec.create(Exec.unwrap(fa).flatMap(f andThen(Exec.unwrap)))

    def tailRecM[A, B](a: A)(f: A => Exec[Either[A, B]]): Exec[B] =
      Exec.create(Monad[Eval].tailRecM(a)(f andThen(Exec.unwrap)))

    def pure[A](x: A): Exec[A] =
      Exec.create(Eval.now(x))
  }

  implicit def catsUExecMonoid[A: Monoid]: Monoid[Exec[A]] = new UExecSemigroup[A] with Monoid[Exec[A]] {
    val empty: Exec[A] = Exec.pure(Monoid[A].empty)
  }
}

private[effect] sealed abstract class UExecInstances0 {
  implicit def catsExecSemigroup[A: Semigroup]: Semigroup[Exec[A]] = new UExecSemigroup[A]
}


private[effect] class UExecSemigroup[A: Semigroup] extends Semigroup[Exec[A]] {
  def combine(x: Exec[A], y: Exec[A]): Exec[A] =
    Exec.create(Exec.unwrap(x).flatMap(a => Exec.unwrap(y).map(Semigroup[A].combine(a, _))))
}
