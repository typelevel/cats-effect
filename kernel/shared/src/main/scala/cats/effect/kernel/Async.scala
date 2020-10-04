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

import cats.syntax.all._

import cats.arrow.FunctionK
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

trait Async[F[_]] extends AsyncPlatform[F] with Sync[F] with Temporal[F] {
  // returns an optional cancelation token
  def async[A](k: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]): F[A] = {
    val body = new Cont[F, A] {
      def apply[G[_]](implicit G: MonadCancel[G, Throwable]) = { (resume, get, lift) =>
        G.uncancelable { poll =>
          lift(k(resume)) flatMap {
            case Some(fin) => G.onCancel(poll(get), lift(fin))
            case None => poll(get)
          }
        }
      }
    }

    cont(body)
  }

  def async_[A](k: (Either[Throwable, A] => Unit) => Unit): F[A] =
    async[A](cb => as(delay(k(cb)), None))

  def never[A]: F[A] = async(_ => pure(none[F[Unit]]))

  // evalOn(executionContext, ec) <-> pure(ec)
  def evalOn[A](fa: F[A], ec: ExecutionContext): F[A]

  def executionContext: F[ExecutionContext]

  def fromFuture[A](fut: F[Future[A]]): F[A] =
    flatMap(fut) { f =>
      flatMap(executionContext) { implicit ec =>
        async_[A](cb => f.onComplete(t => cb(t.toEither)))
      }
    }

  /*
   * NOTE: This is a very low level api, end users should use `async` instead.
   * See cats.effect.kernel.Cont for more detail.
   *
   * If you are an implementor, and you have `async`, `Async.defaultCont`
   * provides an implementation of `cont` in terms of `async`.
   * Note that if you use `defaultCont` you _have_ to override `async`.
   */
  def cont[A](body: Cont[F, A]): F[A]
}

object Async {
  def apply[F[_]](implicit F: Async[F]): F.type = F

  def defaultCont[F[_], A](body: Cont[F, A])(implicit F: Async[F]): F[A] = {
    sealed trait State
    case object Initial extends State
    case class Value(v: Either[Throwable, A]) extends State
    case class Waiting(cb: Either[Throwable, A] => Unit) extends State

    F.delay(new AtomicReference[State](Initial)).flatMap { state =>
        def get: F[A] =
    F.defer {
      state.get match {
        case Value(v) => F.fromEither(v)
        case Initial =>
          F.async { cb =>
            val waiting = Waiting(cb)

            @tailrec
            def loop(): Unit =
              state.get match {
                case s @ Initial =>
                  state.compareAndSet(s, waiting)
                  loop()
                case Waiting(_) => ()
                case Value(v) => cb(v)
              }

            def onCancel = F.delay(state.compareAndSet(waiting, Initial)).void

            F.delay(loop()).as(onCancel.some)
          }
        case Waiting(_) =>
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
        @tailrec
        def loop(): Unit =
          state.get match {
            case Value(_) => () /* idempotent, double calls are forbidden */
            case s @ Initial =>
              if (!state.compareAndSet(s, Value(v))) loop()
              else ()
            case s @ Waiting(cb) =>
              if (state.compareAndSet(s, Value(v))) cb(v)
              else loop()
          }

        loop()
      }


      body[F].apply(resume, get, FunctionK.id)
    }
  }
}
