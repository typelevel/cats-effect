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

trait AsyncRegionLaws[R[_[_], _], F[_]] extends AsyncLaws[R[F, *]] with TemporalRegionLaws[R, F, Throwable] {
  implicit val F: Async[R[F, *]] with Region[R, F, Throwable]
}

object AsyncRegionLaws {
  def apply[
      R[_[_], _],
      F[_]](
    implicit
      F0: Async[R[F, *]] with Region[R, F, Throwable],
      B0: Bracket.Aux[F, Throwable, Outcome[R[F, *], Throwable, *]])
      : AsyncRegionLaws[R, F] =
    new AsyncRegionLaws[R, F] {
      val F = F0
      val B = B0
    }
}
