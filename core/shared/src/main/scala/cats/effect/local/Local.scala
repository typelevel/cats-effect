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
    IO.Local(state => (state, state.get(index).map(_.asInstanceOf[A]).getOrElse(default)))

  def set(value: A): IO[Unit] =
    IO.Local(state => (state + (index -> value), ()))

  def clear: IO[Unit] =
    IO.Local(state => (state - index, ()))

  def update(f: A => A): IO[Unit] =
    get.flatMap(a => set(f(a)))

  def modify[B](f: A => (A, B)): IO[B] =
    get.flatMap { a =>
      val (a2, b) = f(a)
      set(a2).as(b)
    }

  def getAndSet(value: A): IO[A] =
    get <* set(value)

  def getAndClear: IO[A] =
    get <* clear

}

object Local {

  def apply[A](default: A): IO[Local[A]] =
    of(default)

  def of[A](default: A): IO[Local[A]] =
    IO {
      val index = IOFiber.nextLocalIndex()
      new Local(index, default)
    }

}
