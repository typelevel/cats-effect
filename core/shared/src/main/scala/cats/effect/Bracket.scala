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

trait Bracket[F[_], E] extends MonadError[F, E] {
  def bracket[A, B](acquire: F[A])(use: A => F[B])
    (release: (A, BracketResult[E]) => F[Unit]): F[B]
}

sealed abstract class BracketResult[+E]

object BracketResult {
  final case object Completed extends BracketResult[Nothing]
  final case class Error[+E](e: E) extends BracketResult[E]
  final case class Canceled[+E](e: Option[E]) extends BracketResult[E]

  def complete[E]: BracketResult[E] = Completed
  def error[E](e: E): BracketResult[E] = Error[E](e)
  def canceled[E]: BracketResult[E] = Canceled(None)
  def canceledWith[E](e: Option[E]): BracketResult[E] = Canceled(e)

  def attempt[E, A](value: Either[E, A]): BracketResult[E] =
    value match {
      case Left(e) => BracketResult.error(e)
      case Right(_) => BracketResult.complete
    }
}

object Bracket {
  def apply[F[_], E](implicit ev: Bracket[F, E]): Bracket[F, E] = ev
}
