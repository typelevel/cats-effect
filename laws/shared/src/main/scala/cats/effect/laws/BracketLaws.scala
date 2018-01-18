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
package laws

import cats.effect.BracketResult._
import cats.implicits._
import cats.laws._

import scala.util.{Left, Right}

trait BracketLaws[F[_], E] extends MonadErrorLaws[F, E] {
  implicit def F: Bracket[F, E]


  def bracketWithPureUnitIsEqvMap[A, B](fa: F[A], f: A => B) =
    F.bracket(fa)(a => f(a).pure[F])((_, _) => F.unit) <-> F.map(fa)(f)

  def bracketWithPureUnitIsEqvFlatMap[A, B](fa: F[A], f: A => F[B]) =
    F.bracket(fa)(f)((_, _) => F.unit) <-> F.flatMap(fa)(f)


  def bracketEquivalence[A, B](acquire: F[A], use: A => F[B], release: Either[Option[E], Option[B]] => F[Unit]): IsEq[F[B]] = {

    def toEither(r: BracketResult[E, B]): Either[Option[E], Option[B]] = r match {
      case Success(b) => Right(Some(b))
      case Error(oe) => Left(oe)
      case Cancelled() => Right(None)
    }

    val result = acquire.flatMap(a => use(a).attempt).flatMap { eeb =>
      release(eeb.bimap(e => Option(e), b => Option(b))).flatMap(_ => eeb.pure[F].rethrow)
    }
    F.bracket(acquire)(use)((a, res) => release(toEither(res))) <-> result
  }

}

object BracketLaws {
  def apply[F[_], E](implicit F0: Bracket[F, E]): BracketLaws[F, E] = new BracketLaws[F, E] {
    val F = F0
  }
}
