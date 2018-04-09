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
import cats.effect.internals.{EvalNewtype, NonFatal}
import cats.kernel.{Semigroup, Monoid}

object EvalEffImpl extends EvalEffInstances with EvalNewtype {

  def now[A](a: A): EvalEff[A] = create(Eval.now(a))

  def apply[A](thunk: => A): EvalEff[A] = create(Eval.always(thunk))

  def delayCatch[A](thunk: => A): EvalEff[Either[Throwable, A]] =
    create(Eval.always(try {
      Right(thunk)
    } catch {
      case NonFatal(t) => Left(t)
    }))

  implicit def catsEvalEffOps[A](value: EvalEff[A]): EvalEffOps[A] =
    new EvalEffOps(value)
}

sealed class EvalEffOps[A](val value: EvalEff[A]) {
  def unsafeRun: A =
    EvalEffImpl.unwrap(value).value
}

private[effect] sealed abstract class EvalEffInstances extends EvalEffInstances0 {

  implicit val catsEvalEffMonad: Monad[EvalEff] = new Monad[EvalEff] {
    def flatMap[A, B](fa: EvalEff[A])(f: A => EvalEff[B]): EvalEff[B] =
      EvalEffImpl.create(EvalEffImpl.unwrap(fa).flatMap(f andThen(EvalEffImpl.unwrap)))

    def tailRecM[A, B](a: A)(f: A => EvalEff[Either[A, B]]): EvalEff[B] =
      EvalEffImpl.create(Monad[Eval].tailRecM(a)(f andThen(EvalEffImpl.unwrap)))

    def pure[A](x: A): EvalEff[A] =
      EvalEffImpl.create(Eval.now(x))
  }

  implicit def catsEvalEffMonoid[A: Monoid]: Monoid[EvalEff[A]] = new EvalEffMonoid[A] {
    val algebra = Monoid[A]
  }
}

private[effect] sealed abstract class EvalEffInstances0 {
  implicit def catsEvalEffSemigroup[A: Semigroup]: Semigroup[EvalEff[A]] = new EvalEffSemigroup[A] {
    val algebra = Semigroup[A]
  }
}

trait EvalEffMonoid[A] extends EvalEffSemigroup[A] with Monoid[EvalEff[A]] {
  implicit val algebra: Monoid[A]
  val empty: EvalEff[A] = EvalEff.now(algebra.empty)
}

trait EvalEffSemigroup[A] extends Semigroup[EvalEff[A]] {
  implicit val algebra: Semigroup[A]
  def combine(x: EvalEff[A], y: EvalEff[A]): EvalEff[A] =
    EvalEff.create(EvalEffImpl.unwrap(x).flatMap(a => EvalEffImpl.unwrap(y).map(algebra.combine(a, _))))
}
