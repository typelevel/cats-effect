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

package cats.effect

sealed trait IOLocal[A] {

  def get: IO[A]

  def set(value: A): IO[Unit]

  def reset: IO[Unit]

  def update(f: A => A): IO[Unit]

  def modify[B](f: A => (A, B)): IO[B]

  def getAndSet(value: A): IO[A]

  def getAndReset: IO[A]

  def inheritable: Boolean

}

object IOLocal {

  def apply[A](default: A): IO[IOLocal[A]] =
    apply(default: A, inheritable = true)

  def apply[A](default: A, inheritable: Boolean): IO[IOLocal[A]] =
    IO {
      val _inheritable = inheritable
      new IOLocal[A] { self =>
        override def get: IO[A] =
          IO.Local(state => (state, state.get(self).map(_.asInstanceOf[A]).getOrElse(default)))

        override def set(value: A): IO[Unit] =
          IO.Local(state => (state + (self -> value), ()))

        override def reset: IO[Unit] =
          IO.Local(state => (state - self, ()))

        override def update(f: A => A): IO[Unit] =
          get.flatMap(a => set(f(a)))

        override def modify[B](f: A => (A, B)): IO[B] =
          get.flatMap { a =>
            val (a2, b) = f(a)
            set(a2).as(b)
          }

        override def getAndSet(value: A): IO[A] =
          get <* set(value)

        override def getAndReset: IO[A] =
          get <* reset

        override val inheritable: Boolean = _inheritable

      }
    }

}
