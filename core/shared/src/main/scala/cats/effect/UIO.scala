/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

import cats.{Applicative, Monad, MonadError, Monoid, Parallel, Semigroup, ~>}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import cats.effect.internals.IONewtype


object UIOImpl extends UIOInstances with IONewtype {
  private[cats] def create[A](s: IO[A]): Type[A] =
    s.asInstanceOf[Type[A]]

  def fromIO[A](ioa: IO[A]): UIO[Either[Throwable, A]] =
    create(ioa.attempt)

  def runUIO[A](uioa: UIO[A]): IO[A] =
    uioa.asInstanceOf[IO[A]]

  def runEitherIO[A](uioa: UIO[Either[Throwable, A]]): IO[A] =
    MonadError[IO, Throwable].rethrow(runUIO(uioa))

  def unsafeFromIO[A](ioa: IO[A]): UIO[A] = create(ioa)

  def pure[A](x: A): UIO[A] = create(IO.pure(x))

  def apply[A](x: => A): UIO[Either[Throwable, A]] =
    fromIO(IO(x))

  def async[A](k: (Either[Throwable, A] => Unit) => Unit): UIO[Either[Throwable, A]] =
    fromIO(IO.async(k))

  def suspend[A](thunk: => UIO[A]): UIO[A] =
    unsafeFromIO(IO.suspend(runUIO(thunk)))

  val unit: UIO[Unit] =
    pure(())

  def race[A, B](lh: UIO[A], rh: UIO[B]): UIO[Either[A, B]] =
    unsafeFromIO(IO.race(runUIO(lh), runUIO(rh)))


  def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): UIO[Either[Throwable, A]] =
    fromIO(IO.cancelable(k))

  def uncancelable[A](fa: UIO[A]): UIO[A] =
    unsafeFromIO(runUIO(fa).uncancelable)

  def start[A](uioa: UIO[A]): UIO[Fiber[UIO, A]] =
    unsafeFromIO(runUIO(uioa).start.map(uioFiber))


  def fromFuture[A](iof: UIO[Future[A]]): UIO[Either[Throwable, A]] =
    fromIO(IO.fromFuture(runUIO(iof)))

  def shift(implicit timer: Timer[UIO]): UIO[Unit] =
    timer.shift

  def sleep(duration: FiniteDuration)(implicit timer: Timer[UIO]): UIO[Unit] =
    timer.sleep(duration)

  val cancelBoundary: UIO[Unit] = unsafeFromIO(IO.cancelBoundary)

  def racePair[A, B](lh: UIO[A], rh: UIO[B]): UIO[Either[(A, Fiber[UIO, B]), (Fiber[UIO, A], B)]] = {
    import cats.syntax.bifunctor._
    import cats.instances.either._

    UIOImpl.unsafeFromIO(IO.racePair(runUIO(lh), runUIO(rh)).map { e =>
      e.bimap({
        case (a, fiber) => (a, uioFiber(fiber))
      }, {
        case (fiber, b) => (uioFiber(fiber), b)
      })
    })
  }

  private def uioFiber[A](f: Fiber[IO, A]): Fiber[UIO, A] =
    Fiber(unsafeFromIO(f.join), unsafeFromIO(f.cancel))

}

private[effect] abstract class UIOParallelNewtype {

  type Par[A] = Par.Type[A]

  object Par extends IONewtype {

    def fromUIO[A](s: UIO[A]): Type[A] =
      s.asInstanceOf[Type[A]]

    def toUIO[A](s: Type[A]): UIO[A] =
      s.asInstanceOf[UIO[A]]

  }
}


private[effect] sealed abstract class UIOInstances extends UIOParallelNewtype {
  implicit val catsEffectUAsyncForUIO: UConcurrent[UIO] = new UConcurrent[UIO] {
    def tailRecM[A, B](a: A)(f: A => UIO[Either[A, B]]): UIO[B] =
      UIOImpl.create(Monad[IO].tailRecM(a)(f andThen UIOImpl.runUIO))

    def flatMap[A, B](fa: UIO[A])(f: A => UIO[B]): UIO[B] =
      UIOImpl.create(Monad[IO].flatMap(UIOImpl.runUIO(fa))(f andThen UIOImpl.runUIO))

    def pure[A](x: A): UIO[A] =
      UIOImpl.pure(x)

    def delayCatch[A](thunk: => A): UIO[Either[Throwable, A]] =
      UIO(thunk)

    def asyncCatch[A](k: (Either[Throwable, A] => Unit) => Unit): UIO[Either[Throwable, A]] =
      UIO.async(k)

    def cancelableCatch[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): UIO[Either[Throwable, A]] =
      UIO.cancelable(k)

    def start[A](fa: UIO[A]): UIO[Fiber[UIO, A]] =
      UIO.start(fa)

    def uncancelable[A](fa: UIO[A]): UIO[A] =
      UIO.uncancelable(fa)

    def racePair[A, B](fa: UIO[A], fb: UIO[B]): UIO[Either[(A, Fiber[UIO, B]), (Fiber[UIO, A], B)]] =
      UIO.racePair(fa, fb)

    override def bewareDelayNoCatch[A](thunk: => A): UIO[A] =
      UIO.create(IO(thunk))

    override def bewareAsyncNoCatch[A](k: (Either[Throwable, A] => Unit) => Unit): UIO[A] =
      UIO.create(IO.async(k))
  }

  implicit val catsEffectApplicativeForParUIO: Applicative[UIOImpl.Par] = new Applicative[UIOImpl.Par] {
    def pure[A](x: A): UIOImpl.Par[A] = UIOImpl.Par.fromUIO(UIOImpl.pure(x))

    def ap[A, B](ff: UIOImpl.Par[A => B])(fa: UIOImpl.Par[A]): UIOImpl.Par[B] =
      UIOImpl.Par.fromUIO(UIOImpl.create(Parallel.parAp(UIOImpl.runUIO(UIOImpl.Par.toUIO(ff)))(UIOImpl.runUIO(UIOImpl.Par.toUIO(fa)))))
  }

  implicit val catsEffectParallelForUIO: Parallel[UIO, UIOImpl.Par] = new Parallel[UIO, UIOImpl.Par] {
    def applicative: Applicative[UIOImpl.Par] = catsEffectApplicativeForParUIO

    def monad: Monad[UIO] = catsEffectUAsyncForUIO

    def sequential: ~>[UIOImpl.Par, UIO] =
      new ~>[UIOImpl.Par, UIO] { def apply[A](fa: UIOImpl.Par[A]): UIO[A] = UIOImpl.Par.toUIO(fa) }

    def parallel: ~>[UIO, UIOImpl.Par] =
      new ~>[UIO, UIOImpl.Par] { def apply[A](fa: UIO[A]): UIOImpl.Par[A] = UIOImpl.Par.fromUIO(fa) }
  }

  implicit def catsEffectMonoidForUIO[A: Monoid]: Monoid[UIO[A]] = new Monoid[UIO[A]] {
    def empty: UIO[A] = UIOImpl.pure(Monoid[A].empty)
    def combine(x: UIO[A], y: UIO[A]): UIO[A] =
      UIOImpl.create(UIOImpl.runUIO(x).flatMap(a1 => UIOImpl.runUIO(y).map(a2 => Semigroup[A].combine(a1, a2))))
  }
}
