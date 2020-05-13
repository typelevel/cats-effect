/*
 * Copyright 2020 Daniel Spiewak
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

package ce3
package laws

import cats.implicits._

import scala.concurrent.ExecutionContext
import scala.util.{Left, Right}

trait EffectLaws[F[_]] extends AsyncLaws[F] with BracketLaws[F, Throwable] {
  implicit val F: Effect[F]

  def roundTrip[A](fa: F[A]) =
    F.to[F](fa) <-> fa
}

object EffectLaws {
  def apply[F[_]](implicit F0: Effect[F]): EffectLaws[F] =
    new EffectLaws[F] { val F = F0 }
}
