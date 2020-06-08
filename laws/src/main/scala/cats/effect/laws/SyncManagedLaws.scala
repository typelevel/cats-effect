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

trait SyncManagedLaws[R[_[_], _], F[_]] extends SyncLaws[R[F, *]] with RegionLaws[R, F, Throwable] {
  implicit val F: SyncManaged[R, F]

  def roundTrip[A](fa: R[F, A]) =
    F.to[R](fa) <-> fa
}

object SyncManagedLaws {
  def apply[R[_[_], _], F[_]](
    implicit
      F0: SyncManaged[R, F],
      B0: Bracket[F, Throwable] { type Case[A] = Either[Throwable, A] })
      : SyncManagedLaws[R, F] =
    new SyncManagedLaws[R, F] {
      val F = F0
      val B = B0
    }
}
