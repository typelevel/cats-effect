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

package object ce3 {
  type ConcurrentE[F[_]] = Concurrent[F, Throwable]

  type ConcurrentBracket[F[_], E] = Concurrent[F, E] with Bracket[F, E]

  object ConcurrentBracket {
    def apply[F[_], E](implicit F: ConcurrentBracket[F, E]): ConcurrentBracket[F, E] = F
  }

  type ConcurrentRegion[R[_[_], _], F[_], E] = Concurrent[R[F, ?], E] with Region[R, F, E]

  object ConcurrentRegion {
    def apply[R[_[_], _], F[_], E](implicit R: ConcurrentRegion[R, F, E]): ConcurrentRegion[R, F, E] = R
  }
}
