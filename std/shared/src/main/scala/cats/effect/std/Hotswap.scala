/*
 * Copyright 2020 Typelevel
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

package cats.effect.std

import cats.syntax.all._
import cats.effect.kernel.syntax.all._
import cats.effect.kernel.{Concurrent, Ref, Resource}

sealed trait Hotswap[F[_], R] {

  def swap(next: Resource[F, R]): F[R]

  def clear: F[Unit]

}

object Hotswap {

  def create[F[_], R](implicit F: Concurrent[F]): Resource[F, Hotswap[F, R]] = {
    type State = Option[F[Unit]]

    def initialize: F[Ref[F, State]] =
      F.ref(Some(F.pure(())))

    def finalize(state: Ref[F, State]): F[Unit] =
      state.getAndSet(None).flatMap {
        case Some(finalizer) => finalizer
        case None => raise("Hotswap already finalized")
      }

    def raise(message: String): F[Unit] =
      F.raiseError[Unit](new RuntimeException(message))

    Resource.make(initialize)(finalize).map { state =>
      new Hotswap[F, R] {

        override def swap(next: Resource[F, R]): F[R] =
          F.uncancelable { _ =>
            next.allocated.flatMap {
              case (r, finalizer) =>
                swapFinalizer(finalizer).as(r)
            }
          }

        override def clear: F[Unit] =
          swapFinalizer(F.unit).uncancelable

        private def swapFinalizer(next: F[Unit]): F[Unit] =
          state.modify {
            case Some(previous) =>
              Some(next) -> previous.attempt.void
            case None =>
              None -> (next *> raise("Cannot swap after finalization"))
          }.flatten

      }
    }
  }

}
