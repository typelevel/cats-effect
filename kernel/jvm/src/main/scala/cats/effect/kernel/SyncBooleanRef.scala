/*
 * Copyright 2020-2022 Typelevel
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

import scala.annotation.tailrec

import java.util.concurrent.atomic.AtomicBoolean

private[kernel] final class SyncBooleanRef[F[_]] private[this] (ab: AtomicBoolean)(
    implicit F: Sync[F])
    extends Ref[F, Boolean] {

  def this(b: Boolean)(implicit F: Sync[F]) = this(new AtomicBoolean(b))

  def get: F[Boolean] = F.delay(ab.get)

  def set(b: Boolean): F[Unit] = F.delay(ab.set(b))

  override def getAndSet(b: Boolean): F[Boolean] = F.delay(ab.getAndSet(b))

  override def getAndUpdate(f: Boolean => Boolean): F[Boolean] = {
    @tailrec
    def spin: Boolean = {
      val b = ab.get
      val u = f(b)
      if (!ab.compareAndSet(b, u)) spin
      else b
    }
    F.delay(spin)
  }

  def access: F[(Boolean, Boolean => F[Boolean])] =
    F.delay {
      val snapshot = ab.get
      def setter = (b: Boolean) => F.delay(ab.compareAndSet(snapshot, b))
      (snapshot, setter)
    }

  def tryUpdate(f: Boolean => Boolean): F[Boolean] =
    F.map(tryModify(b => (f(b), ())))(_.isDefined)

  def tryModify[B](f: Boolean => (Boolean, B)): F[Option[B]] =
    F.delay {
      val c = ab.get
      val (u, b) = f(c)
      if (ab.compareAndSet(c, u)) Some(b)
      else None
    }

  def update(f: Boolean => Boolean): F[Unit] = {
    @tailrec
    def spin(): Unit = {
      val b = ab.get
      val u = f(b)
      if (!ab.compareAndSet(b, u)) spin()
    }
    F.delay(spin())
  }

  override def updateAndGet(f: Boolean => Boolean): F[Boolean] = {
    @tailrec
    def spin: Boolean = {
      val b = ab.get
      val u = f(b)
      if (!ab.compareAndSet(b, u)) spin
      else u
    }
    F.delay(spin)
  }

  def modify[B](f: Boolean => (Boolean, B)): F[B] = {
    @tailrec
    def spin: B = {
      val c = ab.get
      val (u, b) = f(c)
      if (!ab.compareAndSet(c, u)) spin
      else b
    }
    F.delay(spin)
  }

  def tryModifyState[B](state: State[Boolean, B]): F[Option[B]] = {
    val f = state.runF.value
    tryModify(b => f(b).value)
  }

  def modifyState[B](state: State[Boolean, B]): F[B] = {
    val f = state.runF.value
    modify(b => f(b).value)
  }

}
