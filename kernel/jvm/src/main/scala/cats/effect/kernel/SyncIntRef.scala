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

import java.util.concurrent.atomic.AtomicInteger

private[kernel] final class SyncIntRef[F[_]] private[this] (ai: AtomicInteger)(
    implicit F: Sync[F])
    extends Ref[F, Int] {

  def this(i: Int)(implicit F: Sync[F]) = this(new AtomicInteger(i))

  def get: F[Int] = F.delay(ai.get)

  def set(i: Int): F[Unit] = F.delay(ai.set(i))

  override def getAndSet(i: Int): F[Int] = F.delay(ai.getAndSet(i))

  override def getAndUpdate(f: Int => Int): F[Int] = {
    @tailrec
    def spin: Int = {
      val i = ai.get
      val u = f(i)
      if (!ai.compareAndSet(i, u)) spin
      else i
    }
    F.delay(spin)
  }

  def access: F[(Int, Int => F[Boolean])] =
    F.delay {
      val snapshot = ai.get
      def setter = (i: Int) => F.delay(ai.compareAndSet(snapshot, i))
      (snapshot, setter)
    }

  def tryUpdate(f: Int => Int): F[Boolean] =
    F.map(tryModify(i => (f(i), ())))(_.isDefined)

  def tryModify[B](f: Int => (Int, B)): F[Option[B]] =
    F.delay {
      val c = ai.get
      val (u, b) = f(c)
      if (ai.compareAndSet(c, u)) Some(b)
      else None
    }

  def update(f: Int => Int): F[Unit] = {
    @tailrec
    def spin(): Unit = {
      val i = ai.get
      val u = f(i)
      if (!ai.compareAndSet(i, u)) spin()
    }
    F.delay(spin())
  }

  override def updateAndGet(f: Int => Int): F[Int] = {
    @tailrec
    def spin: Int = {
      val i = ai.get
      val u = f(i)
      if (!ai.compareAndSet(i, u)) spin
      else u
    }
    F.delay(spin)
  }

  def modify[B](f: Int => (Int, B)): F[B] = {
    @tailrec
    def spin: B = {
      val c = ai.get
      val (u, b) = f(c)
      if (!ai.compareAndSet(c, u)) spin
      else b
    }
    F.delay(spin)
  }

  def tryModifyState[B](state: State[Int, B]): F[Option[B]] = {
    val f = state.runF.value
    tryModify(i => f(i).value)
  }

  def modifyState[B](state: State[Int, B]): F[B] = {
    val f = state.runF.value
    modify(i => f(i).value)
  }

}
