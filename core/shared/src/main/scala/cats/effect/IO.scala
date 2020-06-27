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

package cats.effect

import cats.{~>, Group, Monoid, Semigroup, StackSafeMonad}
import cats.implicits._

import scala.annotation.switch
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.atomic.AtomicInteger

sealed abstract class IO[+A] private (private[effect] val tag: Int) {

  def map[B](f: A => B): IO[B] = IO.Map(this, f)

  def flatMap[B](f: A => IO[B]): IO[B] = IO.FlatMap(this, f)

  def handleErrorWith[B >: A](f: Throwable => IO[B]): IO[B] =
    IO.HandleErrorWith(this, f)

  def evalOn(ec: ExecutionContext): IO[A] = IO.EvalOn(this, ec)

  def start: IO[Fiber[IO, Throwable, A @uncheckedVariance]] =
    IO.Start(this)

  def onCase(pf: PartialFunction[Outcome[IO, Throwable, A @uncheckedVariance], IO[Unit]]): IO[A] =
    IO.OnCase(this, oc => if (pf.isDefinedAt(oc)) pf(oc) else IO.unit)

  def onCancel(body: IO[Unit]): IO[A] =
    onCase { case Outcome.Canceled() => body }

  def racePair[B](
      that: IO[B])
      : IO[
        Either[
          (A @uncheckedVariance, Fiber[IO, Throwable, B]),
          (Fiber[IO, Throwable, A @uncheckedVariance], B)]] =
    IO.RacePair(this, that)

  def to[F[_]](implicit F: Effect[F]): F[A @uncheckedVariance] = {
    /*if (F eq IO.effectForIO) {
      asInstanceOf
    } else {*/
      def fiberFrom[A](f: Fiber[F, Throwable, A]): Fiber[IO, Throwable, A] =
        new Fiber[IO, Throwable, A] {
          val cancel = F.to[IO](f.cancel)
          val join = F.to[IO](f.join).map(_.mapK(F.toK[IO]))
        }

      // the casting is unfortunate, but required to work around GADT unification bugs
      this match {
        case IO.Pure(a) => F.pure(a)
        case IO.Delay(thunk) => F.delay(thunk())
        case IO.Error(t) => F.raiseError(t)
        case IO.Async(k) => F.async(k.andThen(_.to[F].map(_.map(_.to[F]))))

        case IO.ReadEC => F.executionContext.asInstanceOf[F[A]]
        case IO.EvalOn(ioa, ec) => F.evalOn(ioa.to[F], ec)

        case IO.Map(ioe, f) => ioe.to[F].map(f)
        case IO.FlatMap(ioe, f) => F.defer(ioe.to[F].flatMap(f.andThen(_.to[F])))
        case IO.HandleErrorWith(ioa, f) => ioa.to[F].handleErrorWith(f.andThen(_.to[F]))

        case IO.OnCase(ioa, f) =>
          val back = F.onCase(ioa.to[F]) {
            case oc => f(oc.mapK(F.toK[IO])).to[F]
          }

          back.asInstanceOf[F[A]]

        case IO.Uncancelable(body) =>
          F uncancelable { poll =>
            val poll2 = new (IO ~> IO) {
              def apply[A](ioa: IO[A]): IO[A] =
                F.to[IO](poll(ioa.to[F]))
            }

            body(poll2).to[F]
          }

        case IO.Canceled => F.canceled.asInstanceOf[F[A]]

        case IO.Start(ioa) =>
          F.start(ioa.to[F]).map(fiberFrom(_)).asInstanceOf[F[A]]

        case IO.RacePair(ioa, iob) =>
          val back = F.racePair(ioa.to[F], iob.to[F]) map { e =>
            e.bimap(
              { case (a, f) => (a, fiberFrom(f)) },
              { case (f, b) => (fiberFrom(f), b) })
          }

          back.asInstanceOf[F[A]]

        case IO.Sleep(delay) => F.sleep(delay).asInstanceOf[F[A]]
      }
    // }
  }

  def unsafeRunAsync(
      ec: ExecutionContext,
      timer: UnsafeTimer)(
      cb: Either[Throwable, A] => Unit)
      : Unit =
    unsafeRunFiber(ec, timer)(cb)

  private def unsafeRunFiber(
      ec: ExecutionContext,
      timer: UnsafeTimer)(
      cb: Either[Throwable, A] => Unit)
      : IOFiber[A @uncheckedVariance] = {

    val fiber = new IOFiber(
      this,
      timer,
      oc => oc.fold(
        (),
        e => cb(Left(e)),
        ioa => cb(Right(ioa.asInstanceOf[IO.Pure[A]].value))))

    fiber.run(ec)
    fiber
  }
}

private[effect] trait IOLowPriorityImplicits0 {

  implicit def semigroupForIO[A: Semigroup]: Semigroup[IO[A]] =
    new IOSemigroup[A]

  protected class IOSemigroup[A](implicit val A: Semigroup[A]) extends Semigroup[IO[A]] {
    def combine(left: IO[A], right: IO[A]) =
      left.flatMap(l => right.map(r => l |+| r))
  }
}

private[effect] trait IOLowPriorityImplicits1 extends IOLowPriorityImplicits0 {

  implicit def monoidForIO[A: Monoid]: Monoid[IO[A]] =
    new IOMonoid[A]

  protected class IOMonoid[A](override implicit val A: Monoid[A]) extends IOSemigroup[A] with Monoid[IO[A]] {
    def empty = IO.pure(A.empty)
  }
}

object IO extends IOLowPriorityImplicits1 {

  def pure[A](value: A): IO[A] = Pure(value)

  def unit: IO[Unit] = pure(())

  def apply[A](thunk: => A): IO[A] = Delay(() => thunk)

  def raiseError(t: Throwable): IO[Nothing] = Error(t)

  def async[A](k: (Either[Throwable, A] => Unit) => IO[Option[IO[Unit]]]): IO[A] = Async(k)

  def canceled: IO[Unit] = Canceled

  // in theory we can probably do a bit better than this; being lazy for now
  def cede: IO[Unit] =
    async(k => apply(k(Right(()))).map(_ => None))

  def uncancelable[A](body: IO ~> IO => IO[A]): IO[A] =
    Uncancelable(body)

  val executionContext: IO[ExecutionContext] = ReadEC

  val never: IO[Nothing] = async(_ => pure(None))

  def sleep(delay: FiniteDuration): IO[Unit] =
    Sleep(delay)

  def toK[F[_]: Effect]: IO ~> F =
    new (IO ~> F) {
      def apply[A](ioa: IO[A]) = ioa.to[F]
    }

  implicit def groupForIO[A: Group]: Group[IO[A]] =
    new IOGroup[A]

  protected class IOGroup[A](override implicit val A: Group[A]) extends IOMonoid[A] with Group[IO[A]] {
    def inverse(ioa: IO[A]) = ioa.map(A.inverse(_))
  }

  implicit val effectForIO: Effect[IO] = new Effect[IO] with StackSafeMonad[IO] {

    def pure[A](x: A): IO[A] =
      IO.pure(x)

    def handleErrorWith[A](fa: IO[A])(f: Throwable => IO[A]): IO[A] =
      fa.handleErrorWith(f)

    def raiseError[A](e: Throwable): IO[A] =
      IO.raiseError(e)

    def async[A](k: (Either[Throwable, A] => Unit) => IO[Option[IO[Unit]]]): IO[A] =
      IO.async(k)

    def evalOn[A](fa: IO[A], ec: ExecutionContext): IO[A] =
      fa.evalOn(ec)

    val executionContext: IO[ExecutionContext] =
      IO.executionContext

    def bracketCase[A, B](acquire: IO[A])(use: A => IO[B])(release: (A, Outcome[IO, Throwable, B]) => IO[Unit]): IO[B] =
      uncancelable { poll =>
        acquire flatMap { a =>
          val finalized = poll(use(a)).onCancel(release(a, Outcome.Canceled()))
          val handled = finalized onError { case e => release(a, Outcome.Errored(e)).attempt.void }
          handled.flatMap(b => release(a, Outcome.Completed(pure(b))).attempt.as(b))
        }
      }

    def monotonic: IO[FiniteDuration] =
      IO(System.nanoTime().nanoseconds)

    def realTime: IO[FiniteDuration] =
      IO(System.currentTimeMillis().millis)

    def sleep(time: FiniteDuration): IO[Unit] =
      IO.sleep(time)

    def canceled: IO[Unit] =
      IO.canceled

    def cede: IO[Unit] = IO.cede

    def racePair[A, B](fa: IO[A], fb: IO[B]): IO[Either[(A, Fiber[IO, Throwable, B]), (Fiber[IO, Throwable, A], B)]] =
      fa.racePair(fb)

    def start[A](fa: IO[A]): IO[Fiber[IO, Throwable, A]] =
      fa.start

    def uncancelable[A](body: IO ~> IO => IO[A]): IO[A] =
      IO.uncancelable(body)

    def toK[G[_]](implicit G: Effect[G]): IO ~> G =
      IO.toK[G]

    def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] =
      fa.flatMap(f)

    def delay[A](thunk: => A): IO[A] = IO(thunk)
  }

  private[effect] final case class Pure[+A](value: A) extends IO[A](0)
  private[effect] final case class Delay[+A](thunk: () => A) extends IO[A](1)
  private[effect] final case class Error(t: Throwable) extends IO[Nothing](2)
  private[effect] final case class Async[+A](k: (Either[Throwable, A] => Unit) => IO[Option[IO[Unit]]]) extends IO[A](3)

  private[effect] case object ReadEC extends IO[ExecutionContext](4)
  private[effect] final case class EvalOn[+A](ioa: IO[A], ec: ExecutionContext) extends IO[A](5)

  private[effect] final case class Map[E, +A](ioe: IO[E], f: E => A) extends IO[A](6)
  private[effect] final case class FlatMap[E, +A](ioe: IO[E], f: E => IO[A]) extends IO[A](7)

  private[effect] final case class HandleErrorWith[+A](ioa: IO[A], f: Throwable => IO[A]) extends IO[A](8)
  private[effect] final case class OnCase[A](ioa: IO[A], f: Outcome[IO, Throwable, A] => IO[Unit]) extends IO[A](9)

  private[effect] final case class Uncancelable[+A](body: IO ~> IO => IO[A]) extends IO[A](10)
  private[effect] case object Canceled extends IO[Unit](11)

  private[effect] final case class Start[A](ioa: IO[A]) extends IO[Fiber[IO, Throwable, A]](12)
  private[effect] final case class RacePair[A, B](ioa: IO[A], iob: IO[B]) extends IO[Either[(A, Fiber[IO, Throwable, B]), (Fiber[IO, Throwable, A], B)]](13)

  private[effect] final case class Sleep(delay: FiniteDuration) extends IO[Unit](14)
}
