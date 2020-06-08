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

// TODO names ("Managed" conflicts with ZIO, but honestly it's a better name for this than Resource or IsoRegion)
trait Managed[R[_[_], _], F[_]] extends Async[R[F, *]] with Region[R, F, Throwable] {

  def to[S[_[_], _]]: PartiallyApplied[S]

  trait PartiallyApplied[S[_[_], _]] {
    def apply[A](rfa: R[F, A])(implicit S: Async[S[F, *]] with Region[S, F, Throwable]): S[F, A]
  }
}
