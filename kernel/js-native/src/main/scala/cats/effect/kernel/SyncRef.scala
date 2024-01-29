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

package cats.effect.kernel

import cats.data.State

private final class SyncRef[F[_], A](private[this] var value: A)(implicit F: Sync[F])
    extends Ref[F, A] {

  def get: F[A] = F.delay(value)

  def set(a: A): F[Unit] = F.delay {
    value = a
  }

  override def getAndSet(a: A): F[A] = F.delay {
    val current = value
    value = a
    current
  }

  override def getAndUpdate(f: A => A): F[A] = F.delay {
    val current = value
    value = f(current)
    current
  }

  def access: F[(A, A => F[Boolean])] = F.delay {
    val snapshot = value
    def setter = (a: A) =>
      F.delay {
        if (value.asInstanceOf[AnyRef] ne snapshot.asInstanceOf[AnyRef]) false
        else {
          value = a
          true
        }
      }
    (snapshot, setter)
  }

  def tryUpdate(f: A => A): F[Boolean] = F.delay {
    value = f(value)
    true
  }

  def tryModify[B](f: A => (A, B)): F[Option[B]] = F.delay {
    val (u, b) = f(value)
    value = u
    Some(b)
  }

  def update(f: A => A): F[Unit] = F.delay {
    value = f(value)
  }

  final override def updateAndGet(f: A => A): F[A] = F.delay {
    value = f(value)
    value
  }

  def modify[B](f: A => (A, B)): F[B] = F.delay {
    val (u, b) = f(value)
    value = u
    b
  }

  def tryModifyState[B](state: State[A, B]): F[Option[B]] = {
    val f = state.runF.value
    tryModify(a => f(a).value)
  }

  def modifyState[B](state: State[A, B]): F[B] = {
    val f = state.runF.value
    modify(a => f(a).value)
  }

}
