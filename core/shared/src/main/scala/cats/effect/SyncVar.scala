/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

package cats
package effect

import cats.data.{StateT, State}
import cats.implicits._

import java.util.concurrent.atomic.AtomicReference

sealed trait SyncVar[F[_], A] {
  def get(implicit F: Sync[F]): F[A]
  def swap(a: A)(implicit F: Sync[F]): F[A]
  def compareAndSet(expect: A, newValue: A)(implicit F: Sync[F]): F[Boolean]

  // atomically run this StateT Monad
  def runStateT[B](state: StateT[F, A, B])(implicit F: Sync[F]): F[B] =
    state.runF.flatMap { fn =>
      def step(u: Unit): F[Either[Unit, B]] =
        for {
          a <- get
          nextAB <- fn(a)
          (nextA, b) = nextAB
          swapped <- compareAndSet(a, nextA)
        } yield if (swapped) Right(b) else Left(())
      F.tailRecM(())(step)
    }

  // atomically run this State Monad
  def runState[B](state: State[A, B])(implicit F: Sync[F]): F[B] =
    F.delay(state.runF.value).flatMap { fn =>
      def step(u: Unit): F[Either[Unit, B]] =
        for {
          a <- get
          nextAB <- F.delay(fn(a).value)
          (nextA, b) = nextAB
          swapped <- compareAndSet(a, nextA)
        } yield if (swapped) Right(b) else Left(())
      F.tailRecM(())(step)
    }
}

object SyncVar {
  private class SyncVarImpl[F[_], A](init: A) extends SyncVar[F, A] {
    val ref = new AtomicReference(init)

    def get(implicit F: Sync[F]): F[A] =
      F.delay(ref.get)

    def swap(a: A)(implicit F: Sync[F]): F[A] =
      F.delay(ref.getAndSet(a))

    def compareAndSet(expect: A, newValue: A)(implicit F: Sync[F]): F[Boolean] =
      F.delay(ref.compareAndSet(expect, newValue))
  }

  def alloc[F[_], A](a: => A)(implicit F: Sync[F]): F[SyncVar[F, A]] =
    F.delay(new SyncVarImpl[F, A](a))
}
