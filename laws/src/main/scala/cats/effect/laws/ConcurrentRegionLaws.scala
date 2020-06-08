/*
 * Copyright 2020 Typelevel
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
package laws

trait ConcurrentRegionLaws[R[_[_], _], F[_], E] extends ConcurrentLaws[R[F, *], E] with RegionLaws[R, F, E] {
  implicit val F: Concurrent[R[F, *], E] with Region[R, F, E]
}

object ConcurrentRegionLaws {
  def apply[
      R[_[_], _],
      F[_],
      E](
    implicit
      F0: Concurrent[R[F, *], E] with Region[R, F, E],
      B0: Bracket.Aux[F, E, Outcome[R[F, *], E, *]])
      : ConcurrentRegionLaws[R, F, E] =
    new ConcurrentRegionLaws[R, F, E] {
      val F = F0
      val B = B0
    }
}
