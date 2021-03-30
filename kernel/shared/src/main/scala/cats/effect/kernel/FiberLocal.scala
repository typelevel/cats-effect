/*
 * Copyright 2020-2021 Typelevel
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

package cats.effect.kernel

trait GenLocal[F[_], E] {

  def F: GenConcurrent[F, E]

  def local[A](default: A): F[FiberLocal[F, A]]
}

trait FiberLocal[F[_], A] {

  def get: F[A]

  def set(value: A): F[Unit]

  def reset: F[Unit]

  def update(f: A => A): F[Unit]

  def modify[B](f: A => (A, B)): F[B]

  def getAndSet(value: A): F[A]

  def getAndReset: F[A]

}

object GenLocal {
  def apply[F[_], E](implicit F: GenLocal[F, E]): F.type = F
  def apply[F[_]](implicit F: GenLocal[F, _], d: DummyImplicit): F.type = F
}
