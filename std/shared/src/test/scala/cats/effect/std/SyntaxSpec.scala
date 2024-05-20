/*
 * Copyright 2020-2024 Typelevel
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

package cats.effect.std

import cats.effect.kernel.{Async, Concurrent, Deferred, GenConcurrent, Ref, Sync}

import org.specs2.mutable.Specification

class SyntaxSpec extends Specification {
  "concurrent data structure construction syntax" >> ok

  def async[F[_]: Async] = {
    Ref.of[F, String]("foo")
    Ref.empty[F, String]
    Ref[F].of(15)
    Ref[F].empty[String]
    Deferred[F, Unit]
    Semaphore[F](15)
  }

  def genConcurrent[F[_]](implicit F: GenConcurrent[F, _]) = {
    Ref.of[F, Int](0)
    Deferred[F, Unit]
  }

  def sync[F[_]](implicit F: Sync[F]) = {
    Ref.of[F, Int](0)
  }

  def preciseConstraints[F[_]: Ref.Make] = {
    Ref.of[F, String]("foo")
    Ref[F].of(15)
    Ref.empty[F, String]
    Ref[F].empty[String]
  }

  def semaphoreIsDeriveable[F[_]](implicit F: Concurrent[F]) =
    Semaphore[F](11)
}
