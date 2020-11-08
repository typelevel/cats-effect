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

package cats.effect.local

import cats.effect.{IO, IOFiber}

final class Local[A] private (index: Int, default: A) {

  def get: IO[A] =
    IO.GetLocal[A](index).map(_.getOrElse(default))

  def set(value: A): IO[Unit] =
    IO.SetLocal[A](index, value)

  def update(f: A => A): IO[Unit] =
    get.flatMap(a => set(f(a)))

  def getAndSet(value: A): IO[A] =
    get <* set(value)

  def clear: IO[Unit] =
    IO.SetLocal[A](index, default)

}

object Local {

  def of[A](default: A): IO[Local[A]] =
    IO {
      val index = IOFiber.nextLocalIndex()
      new Local(index, default)
    }

}
