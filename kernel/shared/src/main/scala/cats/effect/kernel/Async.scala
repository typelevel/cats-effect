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
import cats.data.{EitherT, Ior, IorT, Kleisli, OptionT, WriterT}
import cats.{~>, Monoid, Semigroup}

import cats.arrow.FunctionK
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

trait Async[F[_]] extends AsyncPlatform[F] with Sync[F] with Temporal[F] {
  // returns an optional cancelation token
  def async[A](k: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]): F[A] = {
    val body = new Cont[F, A] {
      def apply[G[_]](implicit G: MonadCancel[G, Throwable]) = { (resume, get, lift) =>
        G.uncancelable { demask =>
          lift(k(resume)) flatMap {
            case Some(fin) => G.onCancel(demask(get), lift(fin))
            case None => demask(get)
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
    case class Initial() extends State
    case class Value(v: Either[Throwable, A]) extends State
    case class Waiting(cb: Either[Throwable, A] => Unit) extends State

    F.delay(new AtomicReference[State](Initial())).flatMap { state =>
      def get: F[A] =
        F.defer {
          state.get match {
            case Value(v) => F.fromEither(v)
            case Initial() =>
              F.async { cb =>
                val waiting = Waiting(cb)

                @tailrec
                def loop(): Unit =
                  state.get match {
                    case s @ Initial() =>
                      state.compareAndSet(s, waiting)
                      loop()
                    case Waiting(_) => ()
                    case Value(v) => cb(v)
                  }

                def onCancel = F.delay(state.compareAndSet(waiting, Initial())).void

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
            case s @ Initial() =>
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

  implicit def asyncForOptionT[F[_]](implicit F0: Async[F]): Async[OptionT[F, *]] =
    new OptionTAsync[F] {
      override implicit protected def F: Async[F] = F0
    }

  implicit def asyncForEitherT[F[_], E](implicit F0: Async[F]): Async[EitherT[F, E, *]] =
    new EitherTAsync[F, E] {
      override implicit protected def F: Async[F] = F0
    }

  implicit def asyncForIorT[F[_], L](
      implicit F0: Async[F],
      L0: Semigroup[L]): Async[IorT[F, L, *]] =
    new IorTAsync[F, L] {
      override implicit protected def F: Async[F] = F0

      override implicit protected def L: Semigroup[L] = L0
    }

  implicit def asyncForWriterT[F[_], L](
      implicit F0: Async[F],
      L0: Monoid[L]): Async[WriterT[F, L, *]] =
    new WriterTAsync[F, L] {
      override implicit protected def F: Async[F] = F0

      override implicit protected def L: Monoid[L] = L0
    }

  implicit def asyncForKleisli[F[_], R](implicit F0: Async[F]): Async[Kleisli[F, R, *]] =
    new KleisliAsync[F, R] {
      override implicit protected def F: Async[F] = F0
    }

  private[effect] trait OptionTAsync[F[_]]
      extends Async[OptionT[F, *]]
      with Sync.OptionTSync[F]
      with Temporal.OptionTTemporal[F, Throwable] {

    implicit protected def F: Async[F]

    def cont[A](body: Cont[OptionT[F, *], A]): OptionT[F, A] =
      OptionT(
        F.cont(
          new Cont[F, Option[A]] {

            override def apply[G[_]](implicit G: MonadCancel[G, Throwable])
                : (Either[Throwable, Option[A]] => Unit, G[Option[A]], F ~> G) => G[Option[A]] =
              (cb, ga, nat) => {
                val natT: OptionT[F, *] ~> OptionT[G, *] =
                  new ~>[OptionT[F, *], OptionT[G, *]] {

                    override def apply[B](fa: OptionT[F, B]): OptionT[G, B] =
                      OptionT(nat(fa.value))

                  }

                body[OptionT[G, *]].apply(e => cb(e.map(Some(_))), OptionT(ga), natT).value
              }
          }
        )
      )

    def evalOn[A](fa: OptionT[F, A], ec: ExecutionContext): OptionT[F, A] =
      OptionT(F.evalOn(fa.value, ec))

    def executionContext: OptionT[F, ExecutionContext] = OptionT.liftF(F.executionContext)

    override def never[A]: OptionT[F, A] = OptionT.liftF(F.never)

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

  private[effect] trait EitherTAsync[F[_], E]
      extends Async[EitherT[F, E, *]]
      with Sync.EitherTSync[F, E]
      with Temporal.EitherTTemporal[F, E, Throwable] {

    implicit protected def F: Async[F]

    def cont[A](body: Cont[EitherT[F, E, *], A]): EitherT[F, E, A] =
      EitherT(
        F.cont(
          new Cont[F, Either[E, A]] {

            override def apply[G[_]](implicit G: MonadCancel[G, Throwable]): (
                Either[Throwable, Either[E, A]] => Unit,
                G[Either[E, A]],
                F ~> G) => G[Either[E, A]] =
              (cb, ga, nat) => {
                val natT: EitherT[F, E, *] ~> EitherT[G, E, *] =
                  new ~>[EitherT[F, E, *], EitherT[G, E, *]] {

                    override def apply[B](fa: EitherT[F, E, B]): EitherT[G, E, B] =
                      EitherT(nat(fa.value))

                  }

                body[EitherT[G, E, *]].apply(e => cb(e.map(Right(_))), EitherT(ga), natT).value
              }
          }
        )
      )

    def evalOn[A](fa: EitherT[F, E, A], ec: ExecutionContext): EitherT[F, E, A] =
      EitherT(F.evalOn(fa.value, ec))

    def executionContext: EitherT[F, E, ExecutionContext] = EitherT.liftF(F.executionContext)

    override def never[A]: EitherT[F, E, A] = EitherT.liftF(F.never)

    override def ap[A, B](
        ff: EitherT[F, E, A => B]
    )(fa: EitherT[F, E, A]): EitherT[F, E, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): EitherT[F, E, A] = delegate.pure(x)

    override def flatMap[A, B](fa: EitherT[F, E, A])(
        f: A => EitherT[F, E, B]): EitherT[F, E, B] =
      delegate.flatMap(fa)(f)

    override def tailRecM[A, B](a: A)(f: A => EitherT[F, E, Either[A, B]]): EitherT[F, E, B] =
      delegate.tailRecM(a)(f)

    override def raiseError[A](e: Throwable): EitherT[F, E, A] =
      delegate.raiseError(e)

    override def handleErrorWith[A](fa: EitherT[F, E, A])(
        f: Throwable => EitherT[F, E, A]): EitherT[F, E, A] =
      delegate.handleErrorWith(fa)(f)

  }

  private[effect] trait IorTAsync[F[_], L]
      extends Async[IorT[F, L, *]]
      with Sync.IorTSync[F, L]
      with Temporal.IorTTemporal[F, L, Throwable] {

    implicit protected def F: Async[F]

    def cont[A](body: Cont[IorT[F, L, *], A]): IorT[F, L, A] =
      IorT(
        F.cont(
          new Cont[F, Ior[L, A]] {

            override def apply[G[_]](implicit G: MonadCancel[G, Throwable])
                : (Either[Throwable, Ior[L, A]] => Unit, G[Ior[L, A]], F ~> G) => G[Ior[L, A]] =
              (cb, ga, nat) => {
                val natT: IorT[F, L, *] ~> IorT[G, L, *] =
                  new ~>[IorT[F, L, *], IorT[G, L, *]] {

                    override def apply[B](fa: IorT[F, L, B]): IorT[G, L, B] =
                      IorT(nat(fa.value))

                  }

                body[IorT[G, L, *]].apply(e => cb(e.map(Ior.Right(_))), IorT(ga), natT).value
              }
          }
        )
      )

    def evalOn[A](fa: IorT[F, L, A], ec: ExecutionContext): IorT[F, L, A] =
      IorT(F.evalOn(fa.value, ec))

    def executionContext: IorT[F, L, ExecutionContext] = IorT.liftF(F.executionContext)

    override def never[A]: IorT[F, L, A] = IorT.liftF(F.never)

    override def ap[A, B](
        ff: IorT[F, L, A => B]
    )(fa: IorT[F, L, A]): IorT[F, L, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): IorT[F, L, A] = delegate.pure(x)

    override def flatMap[A, B](fa: IorT[F, L, A])(f: A => IorT[F, L, B]): IorT[F, L, B] =
      delegate.flatMap(fa)(f)

    override def tailRecM[A, B](a: A)(f: A => IorT[F, L, Either[A, B]]): IorT[F, L, B] =
      delegate.tailRecM(a)(f)

    override def raiseError[A](e: Throwable): IorT[F, L, A] =
      delegate.raiseError(e)

    override def handleErrorWith[A](fa: IorT[F, L, A])(
        f: Throwable => IorT[F, L, A]): IorT[F, L, A] =
      delegate.handleErrorWith(fa)(f)

  }

  private[effect] trait WriterTAsync[F[_], L]
      extends Async[WriterT[F, L, *]]
      with Sync.WriterTSync[F, L]
      with Temporal.WriterTTemporal[F, L, Throwable] {

    implicit protected def F: Async[F]

    def cont[A](body: Cont[WriterT[F, L, *], A]): WriterT[F, L, A] =
      WriterT(
        F.cont(
          new Cont[F, (L, A)] {

            override def apply[G[_]](implicit G: MonadCancel[G, Throwable])
                : (Either[Throwable, (L, A)] => Unit, G[(L, A)], F ~> G) => G[(L, A)] =
              (cb, ga, nat) => {
                val natT: WriterT[F, L, *] ~> WriterT[G, L, *] =
                  new ~>[WriterT[F, L, *], WriterT[G, L, *]] {

                    override def apply[B](fa: WriterT[F, L, B]): WriterT[G, L, B] =
                      WriterT(nat(fa.run))

                  }

                body[WriterT[G, L, *]]
                  .apply(e => cb(e.map((L.empty, _))), WriterT(ga), natT)
                  .run
              }
          }
        )
      )

    def evalOn[A](fa: WriterT[F, L, A], ec: ExecutionContext): WriterT[F, L, A] =
      WriterT(F.evalOn(fa.run, ec))

    def executionContext: WriterT[F, L, ExecutionContext] = WriterT.liftF(F.executionContext)

    override def never[A]: WriterT[F, L, A] = WriterT.liftF(F.never)

    override def ap[A, B](
        ff: WriterT[F, L, A => B]
    )(fa: WriterT[F, L, A]): WriterT[F, L, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): WriterT[F, L, A] = delegate.pure(x)

    override def flatMap[A, B](fa: WriterT[F, L, A])(
        f: A => WriterT[F, L, B]): WriterT[F, L, B] =
      delegate.flatMap(fa)(f)

    override def tailRecM[A, B](a: A)(f: A => WriterT[F, L, Either[A, B]]): WriterT[F, L, B] =
      delegate.tailRecM(a)(f)

    override def raiseError[A](e: Throwable): WriterT[F, L, A] =
      delegate.raiseError(e)

    override def handleErrorWith[A](fa: WriterT[F, L, A])(
        f: Throwable => WriterT[F, L, A]): WriterT[F, L, A] =
      delegate.handleErrorWith(fa)(f)

  }

  private[effect] trait KleisliAsync[F[_], R]
      extends Async[Kleisli[F, R, *]]
      with Sync.KleisliSync[F, R]
      with Temporal.KleisliTemporal[F, R, Throwable] {

    implicit protected def F: Async[F]

    def cont[A](body: Cont[Kleisli[F, R, *], A]): Kleisli[F, R, A] =
      Kleisli(r =>
        F.cont(
          new Cont[F, A] {

            override def apply[G[_]](implicit G: MonadCancel[G, Throwable])
                : (Either[Throwable, A] => Unit, G[A], F ~> G) => G[A] =
              (cb, ga, nat) => {
                val natT: Kleisli[F, R, *] ~> Kleisli[G, R, *] =
                  new ~>[Kleisli[F, R, *], Kleisli[G, R, *]] {

                    override def apply[B](fa: Kleisli[F, R, B]): Kleisli[G, R, B] =
                      Kleisli(r => nat(fa.run(r)))

                  }

                body[Kleisli[G, R, *]].apply(cb, Kleisli.liftF(ga), natT).run(r)
              }
          }
        ))

    def evalOn[A](fa: Kleisli[F, R, A], ec: ExecutionContext): Kleisli[F, R, A] =
      Kleisli(r => F.evalOn(fa.run(r), ec))

    def executionContext: Kleisli[F, R, ExecutionContext] = Kleisli.liftF(F.executionContext)

    override def never[A]: Kleisli[F, R, A] = Kleisli.liftF(F.never)

    override def ap[A, B](
        ff: Kleisli[F, R, A => B]
    )(fa: Kleisli[F, R, A]): Kleisli[F, R, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): Kleisli[F, R, A] = delegate.pure(x)

    override def flatMap[A, B](fa: Kleisli[F, R, A])(
        f: A => Kleisli[F, R, B]): Kleisli[F, R, B] =
      delegate.flatMap(fa)(f)

    override def tailRecM[A, B](a: A)(f: A => Kleisli[F, R, Either[A, B]]): Kleisli[F, R, B] =
      delegate.tailRecM(a)(f)

    override def raiseError[A](e: Throwable): Kleisli[F, R, A] =
      delegate.raiseError(e)

    override def handleErrorWith[A](fa: Kleisli[F, R, A])(
        f: Throwable => Kleisli[F, R, A]): Kleisli[F, R, A] =
      delegate.handleErrorWith(fa)(f)

  }
}
