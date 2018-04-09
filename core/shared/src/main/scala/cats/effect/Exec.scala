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

import cats.{Eval, Monad}
import cats.effect.internals.{ExecNewtype, NonFatal}
import cats.kernel.{Semigroup, Monoid}

object ExecImpl extends ExecInstances with ExecNewtype {

  /**
    * Construct a non-effectful value of `Exec`.
    * This should NOT be used with side-effects as evaluation is eager in this case.
    */
  def now[A](a: A): Exec[A] = create(Eval.now(a))

  /**
    * Suspends a synchronous side effect in `Exec`.
    * Warning: It does not, however catch any Exceptions.
    * Therefore it is recommended to be very conservative with this function
    * and only use it when you are 100% sure the body never throws an Exception.
    * If you're not sure if you should be using this method or not, use `delayCatch`
    */
  def delayNoCatch[A](thunk: => A): Exec[A] = create(Eval.always(thunk))

  /**
    * Suspends a synchronous side effect in `Exec` and catches non-fatal exceptions inside `Either`.
    */
  def apply[A](thunk: => A): Exec[Either[Throwable, A]] =
    create(Eval.always(try {
      Right(thunk)
    } catch {
      case NonFatal(t) => Left(t)
    }))

  implicit def catsEvalEffOps[A](value: Exec[A]): EvalEffOps[A] =
    new EvalEffOps(value)
}

sealed class EvalEffOps[A](val value: Exec[A]) {

  /**
    * Produces the result by running the encapsulated effects as impure
    * side effects.
    *
    * As the name says, this is an UNSAFE function as it is impure and
    * performs side effects. You should ideally only call this function
    * *once*, at the very end of your program.
    */
  def unsafeRun: A =
    ExecImpl.unwrap(value).value
}

private[effect] sealed abstract class ExecInstances extends ExecInstances0 {

  implicit val catsExecMonad: Monad[Exec] = new Monad[Exec] {
    def flatMap[A, B](fa: Exec[A])(f: A => Exec[B]): Exec[B] =
      ExecImpl.create(ExecImpl.unwrap(fa).flatMap(f andThen(ExecImpl.unwrap)))

    def tailRecM[A, B](a: A)(f: A => Exec[Either[A, B]]): Exec[B] =
      ExecImpl.create(Monad[Eval].tailRecM(a)(f andThen(ExecImpl.unwrap)))

    def pure[A](x: A): Exec[A] =
      ExecImpl.create(Eval.now(x))
  }

  implicit def catsExecMonoid[A: Monoid]: Monoid[Exec[A]] = new ExecSemigroup[A] with Monoid[Exec[A]] {
    val empty: Exec[A] = Exec.now(Monoid[A].empty)
  }
}

private[effect] sealed abstract class ExecInstances0 {
  implicit def catsExecSemigroup[A: Semigroup]: Semigroup[Exec[A]] = new ExecSemigroup[A]
}


private[effect] class ExecSemigroup[A: Semigroup] extends Semigroup[Exec[A]] {
  def combine(x: Exec[A], y: Exec[A]): Exec[A] =
    Exec.create(ExecImpl.unwrap(x).flatMap(a => ExecImpl.unwrap(y).map(Semigroup[A].combine(a, _))))
}
