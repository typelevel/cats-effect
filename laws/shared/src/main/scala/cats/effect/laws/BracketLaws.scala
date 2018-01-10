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

import cats.implicits._
import cats.laws._

trait BracketLaws[F[_], E] extends MonadErrorLaws[F, E] {
  implicit def F: Bracket[F, E]

  def releaseIsCalledOnSuccess[A, B](fa: F[A], f: A => B, funit: F[Unit]) =
    F.bracket(fa)(a => f(a).pure[F])((_, _) => funit) <-> fa.map(f).flatTap(_ => funit)

  def releaseIsCalledOnError[A, B](fa: F[A], e: E, funit: F[Unit], f: E => B) =
    F.bracket(fa)(_ => F.raiseError[B](e))((_,_) => funit).handleError(f) <-> fa *> funit *> f(e).pure[F]
}

object BracketLaws {
  def apply[F[_], E](implicit F0: Bracket[F, E]): BracketLaws[F, E] = new BracketLaws[F, E] {
    val F = F0
  }
}
