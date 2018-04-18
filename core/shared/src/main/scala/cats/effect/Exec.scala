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

object ExecImpl extends ExecInstances with ExecNewtype {

  /**
    * Construct a non-effectful value of `Exec`.
    * This should NOT be used with side-effects as evaluation is eager in this case.
    */
  def pure[A](a: A): Exec[A] = create(Eval.now(a))

  /** Alias for `Exec.pure(())`. */
  val unit: Exec[Unit] = pure(())

  /**
    * Suspends a synchronous side effect in `Exec` and catches non-fatal exceptions.
    */
  def apply[A](thunk: => A): Exec[A] =
    create(Eval.always(thunk))

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
    case notNow => apply(notNow.value)
  }

  implicit def catsEvalEffOps[A](value: Exec[A]): ExecOps[A] =
    new ExecOps(value)
}

sealed class ExecOps[A](val value: Exec[A]) {

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


  /**
    * Produces the result by running the encapsulated effects as impure
    * side effects, but catches errors inside Either.
    *
    * As the name says, this is an UNSAFE function as it is impure and
    * performs side effects. You should ideally only call this function
    * *once*, at the very end of your program.
    */
  def unsafeRunToEither: Either[Throwable, A] =
    try { Right(unsafeRun) } catch {
      case NonFatal(t) => Left(t)
    }

  /**
    * Convert this `Exec` to an `IO`, preserving purity and allowing
    * for interop with asynchronous computations.
    */
  def toIO: IO[A] =
    to[IO]

  /**
    * Convert this `Exec` to a `UExec`, catching all errors inside `Either`.
    */
  def toUExec: UExec[Either[Throwable, A]] =
    UExec.create(Exec.unwrap(Sync[Exec].attempt(value)))

  /**
    * Convert this `Exec` to another effect type that implements the [[Sync]] type class.
    */
  def to[F[_]: Sync]: F[A] =
    Sync[F].delay(unsafeRun)
}

private[effect] sealed abstract class ExecInstances extends ExecInstances0 {

  implicit val catsExecSync: Sync[Exec] = new Sync[Exec] {
    def flatMap[A, B](fa: Exec[A])(f: A => Exec[B]): Exec[B] =
      ExecImpl.create(ExecImpl.unwrap(fa).flatMap(f andThen(ExecImpl.unwrap)))

    def tailRecM[A, B](a: A)(f: A => Exec[Either[A, B]]): Exec[B] =
      ExecImpl.create(Monad[Eval].tailRecM(a)(f andThen(ExecImpl.unwrap)))

    def pure[A](x: A): Exec[A] =
      ExecImpl.create(Eval.now(x))

    def handleErrorWith[A](fa: Exec[A])(f: Throwable => Exec[A]): Exec[A] =
      ExecImpl.create(Eval.defer(try Now(fa.unsafeRun) catch { case NonFatal(t) => ExecImpl.unwrap(f(t)) }))

    def raiseError[A](e: Throwable): Exec[A] =
      ExecImpl(throw e)

    def suspend[A](thunk: => Exec[A]): Exec[A] =
      ExecImpl.create(Eval.defer(ExecImpl.unwrap(thunk)))
  }

  implicit def catsExecMonoid[A: Monoid]: Monoid[Exec[A]] = new ExecSemigroup[A] with Monoid[Exec[A]] {
    val empty: Exec[A] = Exec.pure(Monoid[A].empty)
  }
}

private[effect] sealed abstract class ExecInstances0 {
  implicit def catsExecSemigroup[A: Semigroup]: Semigroup[Exec[A]] = new ExecSemigroup[A]
}


private[effect] class ExecSemigroup[A: Semigroup] extends Semigroup[Exec[A]] {
  def combine(x: Exec[A], y: Exec[A]): Exec[A] =
    Exec.create(ExecImpl.unwrap(x).flatMap(a => ExecImpl.unwrap(y).map(Semigroup[A].combine(a, _))))
}
