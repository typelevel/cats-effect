/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

import cats.effect.internals.{FiberRefId, IOFiberRef}

final class FiberRef[A] private (refId: FiberRefId, initial: A) {

  def get: IO[A] =
    IOFiberRef.get(refId).map(_.getOrElse(initial))

  def set(value: A): IO[Unit] =
    IOFiberRef.set(refId, value)

  def getAndSet(value: A): IO[A] =
    get.flatMap { a =>
      set(value).as(a)
    }

  def update(f: A => A): IO[Unit] =
    get.flatMap { a =>
      set(f(a))
    }

  def getAndUpdate(f: A => A): IO[A] =
    get.flatMap { a =>
      val newA = f(a)
      set(newA).as(a)
    }

  def updateAndGet(f: A => A): IO[A] =
    get.flatMap { a =>
      val newA = f(a)
      set(newA).as(newA)
    }

  def modify[B](f: A => (A, B)): IO[B] =
    get.flatMap { a =>
      val (newA, b) = f(a)
      set(newA).as(b)
    }

  def reset: IO[Unit] =
    set(initial)

}

object FiberRef {

  def of[A](initial: A): IO[FiberRef[A]] =
    for {
      refId <- IOFiberRef.allocate
    } yield new FiberRef[A](refId, initial)

}
