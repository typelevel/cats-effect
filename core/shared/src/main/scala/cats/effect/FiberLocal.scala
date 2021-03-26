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

trait FiberLocal[F[_], A] {

  def get: F[A]

  def set(value: A): F[Unit]

  def clear: F[Unit]

  def update(f: A => A): F[Unit]

  def modify[B](f: A => (A, B)): F[B]

  def getAndSet(value: A): F[A]

  def getAndClear: F[A]

}

object FiberLocal {

  def apply[A](default: A): IO[FiberLocal[IO, A]] =
    IO {
      new FiberLocal[IO, A] { self =>
        override def get: IO[A] =
          IO.Local(state => (state, state.get(self).map(_.asInstanceOf[A]).getOrElse(default)))

        override def set(value: A): IO[Unit] =
          IO.Local(state => (state + (self -> value), ()))

        override def clear: IO[Unit] =
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

        override def getAndClear: IO[A] =
          get <* clear

      }
    }

}
