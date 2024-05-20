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

import scala.scalajs.js

private[kernel] trait AsyncPlatform[F[_]] { this: Async[F] =>

  def fromPromise[A](iop: F[js.Promise[A]]): F[A] = fromThenable(widen(iop))

  def fromPromiseCancelable[A](iop: F[(js.Promise[A], F[Unit])]): F[A] =
    fromThenableCancelable(widen(iop))

  def fromThenable[A](iot: F[js.Thenable[A]]): F[A] =
    flatMap(iot) { t =>
      async_[A] { cb =>
        t.`then`[Unit](mkOnFulfilled(cb), js.defined(mkOnRejected(cb)))
        ()
      }
    }

  def fromThenableCancelable[A](iot: F[(js.Thenable[A], F[Unit])]): F[A] =
    flatMap(iot) {
      case (t, fin) =>
        async[A] { cb =>
          as(delay(t.`then`[Unit](mkOnFulfilled(cb), js.defined(mkOnRejected(cb)))), Some(fin))
        }
    }

  @inline private[this] def mkOnFulfilled[A](
      cb: Either[Throwable, A] => Unit): js.Function1[A, js.UndefOr[js.Thenable[Unit]]] =
    (v: A) => cb(Right(v)): js.UndefOr[js.Thenable[Unit]]

  @inline private[this] def mkOnRejected[A](
      cb: Either[Throwable, A] => Unit): js.Function1[Any, js.UndefOr[js.Thenable[Unit]]] = {
    (a: Any) =>
      val e = a match {
        case th: Throwable => th
        case _ => js.JavaScriptException(a)
      }

      cb(Left(e)): js.UndefOr[js.Thenable[Unit]]
  }
}
