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

trait FiberRef[F[_], A] extends Ref[F, A] {

  /**
   * Divorces the current reference from parent fiber and
   * sets a new reference for the current fiber and children
   * fibers.
   */
  def reset: F[Unit]
}

object FiberRef {

  def apply[A](default: A): IO[FiberRef[IO, A]] =
    for {
      ref <- Ref.of[IO, A](default)
      local <- FiberLocal[Ref[IO, A]](ref)
    } yield new FiberRef[IO, A] {
      override def reset: IO[Unit] =
        Ref.of[IO, A](default).flatMap { nextRef => local.set(nextRef) }

      override def get: IO[A] =
        local.get.flatMap(_.get)

      override def set(a: A): IO[Unit] =
        local.get.flatMap(_.set(a))

      override def access: IO[(A, A => IO[Boolean])] =
        local.get.flatMap(_.access)

      override def tryUpdate(f: A => A): IO[Boolean] =
        local.get.flatMap(_.tryUpdate(f))

      override def tryModify[B](f: A => (A, B)): IO[Option[B]] =
        local.get.flatMap(_.tryModify(f))

      override def update(f: A => A): IO[Unit] =
        local.get.flatMap(_.update(f))

      override def modify[B](f: A => (A, B)): IO[B] =
        local.get.flatMap(_.modify(f))

      override def tryModifyState[B](state: cats.data.State[A, B]): IO[Option[B]] =
        local.get.flatMap(_.tryModifyState(state))

      override def modifyState[B](state: cats.data.State[A, B]): IO[B] =
        local.get.flatMap(_.modifyState(state))
    }

}
