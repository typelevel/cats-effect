/*
 * Copyright 2017 Typelevel
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




trait Bracket[F[_], E] extends MonadError[F, E] {
  def bracket[A, B](acquire: F[A])(use: A => F[B])
    (release: (A, BracketResult[E, B]) => F[Unit]): F[B]
}

sealed abstract class BracketResult[E, A] { self =>
  def toEither: Either[Option[E], A] = self match {
    case BracketResult.Success(a) => Right(a)
    case BracketResult.Error(oe) => Left(oe)
    case BracketResult.Cancelled() => Left(None)
  }
}

object BracketResult {
  final case class Success[E, A](a: A) extends BracketResult[E, A]
  final case class Error[E, A](e: Option[E]) extends BracketResult[E, A]
  final case class Cancelled[E, A]() extends BracketResult[E, A]

  def cancelled[E, A]: BracketResult[E, A] = Cancelled[E, A]
  def error[E, A](e: Option[E]): BracketResult[E, A] = Error[E, A](e)
  def success[E, A](a: A): BracketResult[E, A] = Success[E, A](a)

  def fromEither[E, A](e: Either[Option[E], A]): BracketResult[E, A] =
    e match {
      case Left(o) => BracketResult.error(o)
      case Right(b) => BracketResult.success(b)
    }
}


object Bracket {
  def apply[F[_], E](implicit ev: Bracket[F, E]): Bracket[F, E] = ev
}
