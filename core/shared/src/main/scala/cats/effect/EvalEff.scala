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

import cats.Eval
import cats.data.EitherT
import cats.effect.internals.{EitherEvalNewtype, NonFatal}

object EvalEffImpl extends EvalEffInstances with EitherEvalNewtype {

  implicit def catsEvalEffOps[A](value: EvalEff[A]): EvalEffOps[A] =
    new EvalEffOps(value)
}

sealed class EvalEffOps[A](val value: EvalEff[A]) {
  def unsafeToEitherT: EitherT[Eval, Throwable, A] =
    EitherT(unsafeToEval)

  def unsafeToEval: Eval[Either[Throwable, A]] =
    EvalEffImpl.unwrap(value)
}

private[effect] sealed abstract class EvalEffInstances {

  implicit val catsEvalEffSync: Sync[EvalEff] =
    new Sync[EvalEff] {

      def pure[A](x: A): EvalEff[A] = EvalEff(Eval.now(Right(x)))

      def handleErrorWith[A](fa: EvalEff[A])(f: Throwable => EvalEff[A]) =
        EvalEff(EvalEffImpl.unwrap(fa).flatMap(_.fold(f.andThen(_.unsafeToEval), a => Eval.now(Right(a)))))

      def raiseError[A](e: Throwable) =
        EvalEff(Eval.now(Left(e)))

      def flatMap[A, B](fa: EvalEff[A])(f: A => EvalEff[B]): EvalEff[B] =
        EvalEff(fa.unsafeToEitherT.flatMap(f andThen(_.unsafeToEitherT)).value)

      def tailRecM[A, B](a: A)(f: A => EvalEff[Either[A, B]]): EvalEff[B] =
        EvalEff(EitherT.catsDataMonadErrorForEitherT[Eval, Throwable].tailRecM(a)(f andThen (_.unsafeToEitherT)).value)

      def suspend[A](thunk: => EvalEff[A]): EvalEff[A] =
        EvalEff(
          Eval.always(try {
            EvalEffImpl.unwrap(thunk).value
          } catch {
            case NonFatal(t) => Left(t)
          })
        )
    }
}
