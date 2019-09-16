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
package internals

import java.util.concurrent.atomic.AtomicReference
import scala.util.Either

private[effect] object CancelableF {

  /**
   * Implementation for [[Concurrent.cancelableF]].
   */
  def apply[F[_], A](k: (Either[Throwable, A] => Unit) => F[CancelToken[F]])(implicit F: Concurrent[F]): F[A] =
    F.asyncF { cb =>
      val state = new AtomicReference[Either[Throwable, Unit] => Unit](null)
      val cb1 = (a: Either[Throwable, A]) => {
        try {
          cb(a)
        } finally {
          // This CAS can only succeed in case the operation is already finished
          // and no cancellation token was installed yet
          if (!state.compareAndSet(null, Callback.dummy1)) {
            val cb2 = state.get()
            state.lazySet(null)
            cb2(Callback.rightUnit)
          }
        }
      }
      // Until we've got a cancellation token, the task needs to be evaluated
      // uninterruptedly, otherwise risking a leak, hence the bracket
      F.bracketCase(k(cb1)) { _ =>
        F.async[Unit] { cb =>
          if (!state.compareAndSet(null, cb)) {
            cb(Callback.rightUnit)
          }
        }
      } { (token, e) =>
        e match {
          case ExitCase.Canceled => token
          case _                 => F.unit
        }
      }
    }
}
