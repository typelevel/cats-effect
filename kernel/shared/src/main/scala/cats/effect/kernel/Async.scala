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

import cats.implicits._
import cats.data.{OptionT}

import scala.concurrent.{ExecutionContext, Future}

trait Async[F[_]] extends AsyncPlatform[F] with Sync[F] with Temporal[F, Throwable] {

  // returns an optional cancelation token
  def async[A](k: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]): F[A]

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

  implicit def asyncForOptionT[F[_]](implicit F0: Async[F]): Async[OptionT[F, *]] =
    new OptionTAsync[F] {
      override implicit protected def F: Async[F] = F0
    }

  private[effect] trait OptionTAsync[F[_]]
      extends Async[OptionT[F, *]]
      with Sync.OptionTSync[F]
      with Temporal.OptionTTemporal[F, Throwable] {

    implicit protected def F: Async[F]

    def async[A](k: (Either[Throwable, A] => Unit) => OptionT[F, Option[OptionT[F, Unit]]])
        : OptionT[F, A] =
      OptionT.liftF(F.async(k.andThen(_.value.map(_.flatten.map(_.value.void)))))

    def evalOn[A](fa: OptionT[F, A], ec: ExecutionContext): OptionT[F, A] =
      OptionT(F.evalOn(fa.value, ec))

    def executionContext: OptionT[F, ExecutionContext] = OptionT.liftF(F.executionContext)

    override def never[A]: OptionT[F, A] = OptionT.liftF(F.never)

    // protected def delegate: Applicative[OptionT[F, *]] = OptionT.catsDataMonadForOptionT[F]

    override def ap[A, B](
        ff: OptionT[F, A => B]
    )(fa: OptionT[F, A]): OptionT[F, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): OptionT[F, A] = delegate.pure(x)

    override def flatMap[A, B](fa: OptionT[F, A])(f: A => OptionT[F, B]): OptionT[F, B] =
      delegate.flatMap(fa)(f)

    override def tailRecM[A, B](a: A)(f: A => OptionT[F, Either[A, B]]): OptionT[F, B] =
      delegate.tailRecM(a)(f)

    override def raiseError[A](e: Throwable): OptionT[F, A] =
      delegate.raiseError(e)

    override def handleErrorWith[A](fa: OptionT[F, A])(
        f: Throwable => OptionT[F, A]): OptionT[F, A] =
      delegate.handleErrorWith(fa)(f)

  }
}
