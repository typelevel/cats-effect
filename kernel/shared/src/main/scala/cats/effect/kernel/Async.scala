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

import scala.concurrent.{ExecutionContext, Future}

trait Async[F[_]] extends AsyncPlatform[F] with Sync[F] with Temporal[F, Throwable] {

  type CPS[A]
  def MonadCancelForCPS: MonadCancel[CPS, Throwable]
  def liftToCPS[A](fa: F[A]): CPS[A]

  def cps[A, B](body: (Either[Throwable, A] => Unit, CPS[A]) => CPS[B]): F[B]

  // returns an optional cancelation token
  def async[A](k: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]): F[A] =
    cps[A, A] { (cb, get) =>
      MonadCancelForCPS uncancelable { poll =>
        MonadCancelForCPS.flatMap(liftToCPS(k(cb))) {
          case Some(fin) =>
            MonadCancelForCPS.onCancel(poll(get), liftToCPS(fin))

          case None =>
            poll(get)
        }
      }
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
}

object Async {

  def apply[F[_]](implicit F: Async[F]): F.type = F

  implicit def monadCancelForCPS[F[_]](implicit F: Async[F]): MonadCancel[F.CPS, Throwable] =
    F.MonadCancelForCPS
}
