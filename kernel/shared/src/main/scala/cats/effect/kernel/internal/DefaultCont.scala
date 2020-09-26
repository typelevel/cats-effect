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

package cats.effect.kernel
package internal

import cats.syntax.all._
import cats.arrow.FunctionK
import java.util.concurrent.atomic.AtomicReference


private[kernel] final class DefaultCont[F[_], A](implicit F: Async[F]) {
  import DefaultCont._

  /* shared mutable state */
  private[this] val state = new AtomicReference[State[A]](Initial())

  def get: F[A] = F.defer {
    state.get match {
      case Value(v) => F.fromEither(v)
      case Initial() => F.async { cb =>

        val waiting = Waiting(cb)

        def loop(): Unit = state.get match {
          case s @ Initial() =>
            state.compareAndSet(s, waiting)
            loop()
          case Waiting(_) => ()
          case Value(v) => cb(v)
        }

        def onCancel = F.delay(state.compareAndSet(waiting, Initial())).void

        F.delay(loop()).as(onCancel.some)
      }
      case Waiting(_)  =>
        /*
         * - `cont` forbids concurrency, so no other `get` can be in Waiting.
         * -  if a previous get has succeeded or failed and we are being sequenced
         *    afterwards, it means `resume` has set the state to `Value`.
         * - if a previous `get` has been interrupted and we are running as part of 
         *   its finalisers, the state would have been either restored to `Initial`
         *   by the finaliser of that `get`, or set to `Value` by `resume`
         */
        sys.error("Impossible")
    }
  }

  def resume(v: Either[Throwable, A]): Unit = {
    def loop(): Unit = state.get match {
      case Value(_) => () /* idempotent, double calls are forbidden */
      case s @ Initial() =>
        if (!state.compareAndSet(s, Value(v))) loop()
        else ()
      case Waiting(cb) => cb(v)
    }
  }
}
private[kernel] object DefaultCont {
  sealed trait State[A]
  case class Initial[A]() extends State[A] 
  case class Value[A](v: Either[Throwable, A]) extends State[A]
  case class Waiting[A](cb: Either[Throwable, A] => Unit) extends State[A]


  def cont[F[_]: Async, A](body: Cont[F, A]): F[A] =
    Async[F].delay(new DefaultCont[F, A]).flatMap { cont =>
      body[F].apply(cont.resume, cont.get, FunctionK.id)
    }
}
