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

trait TemporalBracketLaws[F[_], E] extends TemporalLaws[F, E] with ConcurrentBracketLaws[F, E] {
  implicit val F: Temporal[F, E] with Bracket[F, E]
}

object TemporalBracketLaws {
  def apply[F[_], E](implicit F0: Temporal[F, E] with Bracket[F, E]): TemporalBracketLaws[F, E] =
    new TemporalBracketLaws[F, E] { val F = F0 }
}
