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

package cats.effect.std

import cats.effect.kernel._
import cats.syntax.all._

sealed trait Shared[F[_], A] {
  def resource: Resource[F, A]
}

object Shared {
  def allocate[F[_], A](
      resource: Resource[F, A]
  )(implicit F: Concurrent[F]): Resource[F, (Shared[F, A], A)] = {
    final case class State(value: A, finalizer: F[Unit], permits: Int) {
      def addPermit: State = copy(permits = permits + 1)
      def releasePermit: State = copy(permits = permits - 1)
    }

    MonadCancel[Resource[F, *]].uncancelable { poll =>
      for {
        underlying <- poll(Resource.eval(resource.allocated))
        state <- Resource.eval(
          F.ref[Option[State]](Some(State(underlying._1, underlying._2, 0))))
        shared = new Shared[F, A] {
          def acquire: F[A] =
            state.modify {
              case Some(st) => (Some(st.addPermit), F.pure(st.value))
              case None =>
                (None, F.raiseError[A](new Throwable("finalization has already occurred")))
            }.flatten

          def release: F[Unit] =
            state.modify {
              case Some(st) if st.permits > 1 => (Some(st.releasePermit), F.unit)
              case Some(st) => (None, st.finalizer)
              case None => (None, F.raiseError[Unit](new Throwable("can't finalize")))
            }.flatten

          override def resource: Resource[F, A] =
            Resource.make(acquire)(_ => release)
        }
        _ <- shared.resource
      } yield shared -> underlying._1
    }
  }
}
