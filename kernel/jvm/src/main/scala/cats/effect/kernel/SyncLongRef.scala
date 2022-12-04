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

import java.util.concurrent.atomic.AtomicLong

private[kernel] final class SyncLongRef[F[_]] private[this] (al: AtomicLong)(
    implicit F: Sync[F])
    extends Ref[F, Long] {

  def this(l: Long)(implicit F: Sync[F]) = this(new AtomicLong(l))

  def get: F[Long] = F.delay(al.get)

  def set(l: Long): F[Unit] = F.delay(al.set(l))

  override def getAndSet(l: Long): F[Long] = F.delay(al.getAndSet(l))

  override def getAndUpdate(f: Long => Long): F[Long] = {
    @tailrec
    def spin: Long = {
      val l = al.get
      val u = f(l)
      if (!al.compareAndSet(l, u)) spin
      else l
    }
    F.delay(spin)
  }

  def access: F[(Long, Long => F[Boolean])] =
    F.delay {
      val snapshot = al.get
      def setter = (l: Long) => F.delay(al.compareAndSet(snapshot, l))
      (snapshot, setter)
    }

  def tryUpdate(f: Long => Long): F[Boolean] =
    F.map(tryModify(l => (f(l), ())))(_.isDefined)

  def tryModify[B](f: Long => (Long, B)): F[Option[B]] =
    F.delay {
      val c = al.get
      val (u, b) = f(c)
      if (al.compareAndSet(c, u)) Some(b)
      else None
    }

  def update(f: Long => Long): F[Unit] = {
    @tailrec
    def spin(): Unit = {
      val l = al.get
      val u = f(l)
      if (!al.compareAndSet(l, u)) spin()
    }
    F.delay(spin())
  }

  override def updateAndGet(f: Long => Long): F[Long] = {
    @tailrec
    def spin: Long = {
      val l = al.get
      val u = f(l)
      if (!al.compareAndSet(l, u)) spin
      else u
    }
    F.delay(spin)
  }

  def modify[B](f: Long => (Long, B)): F[B] = {
    @tailrec
    def spin: B = {
      val c = al.get
      val (u, b) = f(c)
      if (!al.compareAndSet(c, u)) spin
      else b
    }
    F.delay(spin)
  }

  def tryModifyState[B](state: State[Long, B]): F[Option[B]] = {
    val f = state.runF.value
    tryModify(l => f(l).value)
  }

  def modifyState[B](state: State[Long, B]): F[B] = {
    val f = state.runF.value
    modify(l => f(l).value)
  }

}
