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

import scala.annotation.tailrec

import java.util.concurrent.atomic.AtomicReference

private final class SyncRef[F[_], A] private[this] (ar: AtomicReference[A])(implicit F: Sync[F])
    extends Ref[F, A] {

  def this(a: A)(implicit F: Sync[F]) = this(new AtomicReference(a))

  def get: F[A] = F.delay(ar.get)

  def set(a: A): F[Unit] = F.delay(ar.set(a))

  override def getAndSet(a: A): F[A] = F.delay(ar.getAndSet(a))

  override def getAndUpdate(f: A => A): F[A] = {
    @tailrec
    def spin: A = {
      val a = ar.get
      val u = f(a)
      if (!ar.compareAndSet(a, u)) spin
      else a
    }
    F.delay(spin)
  }

  def access: F[(A, A => F[Boolean])] =
    F.delay {
      val snapshot = ar.get
      def setter = (a: A) => F.delay(ar.compareAndSet(snapshot, a))
      (snapshot, setter)
    }

  def tryUpdate(f: A => A): F[Boolean] =
    F.map(tryModify(a => (f(a), ())))(_.isDefined)

  def tryModify[B](f: A => (A, B)): F[Option[B]] =
    F.delay {
      val c = ar.get
      val (u, b) = f(c)
      if (ar.compareAndSet(c, u)) Some(b)
      else None
    }

  def update(f: A => A): F[Unit] = {
    @tailrec
    def spin(): Unit = {
      val a = ar.get
      val u = f(a)
      if (!ar.compareAndSet(a, u)) spin()
    }
    F.delay(spin())
  }

  override def updateAndGet(f: A => A): F[A] = {
    @tailrec
    def spin: A = {
      val a = ar.get
      val u = f(a)
      if (!ar.compareAndSet(a, u)) spin
      else u
    }
    F.delay(spin)
  }

  def modify[B](f: A => (A, B)): F[B] = {
    @tailrec
    def spin: B = {
      val c = ar.get
      val (u, b) = f(c)
      if (!ar.compareAndSet(c, u)) spin
      else b
    }
    F.delay(spin)
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
