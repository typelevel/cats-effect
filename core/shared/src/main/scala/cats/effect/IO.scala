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

import cats.{~>, Monoid, Semigroup, StackSafeMonad}
import cats.implicits._

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

sealed abstract class IO[+A] private (private[effect] val tag: Int) {

  def map[B](f: A => B): IO[B] = IO.Map(this, f)

  def flatMap[B](f: A => IO[B]): IO[B] = IO.FlatMap(this, f)

  def handleErrorWith[B >: A](f: Throwable => IO[B]): IO[B] =
    IO.HandleErrorWith(this, f)

  def evalOn(ec: ExecutionContext): IO[A] = IO.EvalOn(this, ec)

  def start: IO[Fiber[IO, Throwable, A @uncheckedVariance]] =
    IO.Start(this)

  def onCase(pf: PartialFunction[Outcome[IO, Throwable, A @uncheckedVariance], IO[Unit]]): IO[A] =
    IO.OnCase(this, (oc: Outcome[IO, Throwable, A]) => if (pf.isDefinedAt(oc)) pf(oc) else IO.unit)

  def onCancel(body: IO[Unit]): IO[A] =
    onCase { case Outcome.Canceled() => body }

  def guarantee(finalizer: IO[Unit]): IO[A] =
    IO.uncancelable(p => p(this).onCase({ case _ => finalizer }))

  def racePair[B](
      that: IO[B])
      : IO[
        Either[
          (A @uncheckedVariance, Fiber[IO, Throwable, B]),
          (Fiber[IO, Throwable, A @uncheckedVariance], B)]] =
    IO.RacePair(this, that)

  def bracketCase[B](use: A => IO[B])(release: (A, Outcome[IO, Throwable, B]) => IO[Unit]): IO[B] =
    IO uncancelable { poll =>
      flatMap { a =>
        val finalized = poll(use(a)).onCancel(release(a, Outcome.Canceled()))
        val handled = finalized onError { case e => release(a, Outcome.Errored(e)).attempt.void }
        handled.flatMap(b => release(a, Outcome.Completed(IO.pure(b))).attempt.as(b))
      }
    }

  def bracket[B](use: A => IO[B])(release: A => IO[Unit]): IO[B] =
    bracketCase(use)((a, _) => release(a))

  def to[F[_]](implicit F: Effect[F]): F[A @uncheckedVariance] = {
    // re-comment this fast-path to test the implementation with IO itself
    if (F eq IO.effectForIO) {
      asInstanceOf[F[A]]
    } else {
      def fiberFrom[B](f: Fiber[F, Throwable, B]): Fiber[IO, Throwable, B] =
        new Fiber[IO, Throwable, B] {
          val cancel = F.to[IO](f.cancel)
          val join = F.to[IO](f.join).map(_.mapK(F.toK[IO]))
        }

      // the casting is unfortunate, but required to work around GADT unification bugs
      this match {
        case IO.Pure(a) => F.pure(a)
        case IO.Delay(thunk) => F.delay(thunk())
        case IO.Error(t) => F.raiseError(t)
        case IO.Async(k) => F.async(k.andThen(_.to[F].map(_.map(_.to[F]))))

        case _: IO.ReadEC.type => F.executionContext.asInstanceOf[F[A]]
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
              def apply[B](ioa: IO[B]): IO[B] =
                F.to[IO](poll(ioa.to[F]))
            }

            body(poll2).to[F]
          }

        case _: IO.Canceled.type => F.canceled.asInstanceOf[F[A]]

        case self: IO.Start[_] =>
          F.start(self.ioa.to[F]).map(fiberFrom(_)).asInstanceOf[F[A]]

        case self: IO.RacePair[a, b] =>
          val back = F.racePair(self.ioa.to[F], self.iob.to[F]) map { e =>
            e.bimap(
              { case (a, f) => (a, fiberFrom(f)) },
              { case (f, b) => (fiberFrom(f), b) })
          }

          back.asInstanceOf[F[A]]

        case self: IO.Sleep => F.sleep(self.delay).asInstanceOf[F[A]]
        case _: IO.RealTime.type => F.realTime.asInstanceOf[F[A]]
        case _: IO.Monotonic.type => F.monotonic.asInstanceOf[F[A]]

        case _: IO.Cede.type => F.cede.asInstanceOf[F[A]]

        case IO.Unmask(ioa, _) => ioa.to[F]   // polling should be handled by F
      }
    }
  }

  def unsafeRunAsync(
      ec: ExecutionContext,
      timer: UnsafeTimer)(
      cb: Either[Throwable, A] => Unit)
      : Unit =
    unsafeRunFiber(ec, timer)(cb)

  private[effect] def unsafeRunFiber(
      ec: ExecutionContext,
      timer: UnsafeTimer)(
      cb: Either[Throwable, A] => Unit)
      : IOFiber[A @uncheckedVariance] = {

    val fiber = new IOFiber(
      this,
      timer,
      (oc: Outcome[IO, Throwable, A]) => oc.fold(
        (),
        e => cb(Left(e)),
        ioa => cb(Right(ioa.asInstanceOf[IO.Pure[A]].value))))

    ec.execute(() => fiber.run(ec))
    fiber
  }
}

private[effect] trait IOLowPriorityImplicits {

  implicit def semigroupForIO[A: Semigroup]: Semigroup[IO[A]] =
    new IOSemigroup[A]

  protected class IOSemigroup[A](implicit val A: Semigroup[A]) extends Semigroup[IO[A]] {
    def combine(left: IO[A], right: IO[A]) =
      left.flatMap(l => right.map(r => l |+| r))
  }
}

object IO extends IOLowPriorityImplicits {

  def pure[A](value: A): IO[A] = Pure(value)

  val unit: IO[Unit] = pure(())

  def apply[A](thunk: => A): IO[A] = Delay(() => thunk)

  def raiseError(t: Throwable): IO[Nothing] = Error(t)

  def async[A](k: (Either[Throwable, A] => Unit) => IO[Option[IO[Unit]]]): IO[A] = Async(k)

  def async_[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A] =
    async(cb => apply { k(cb); None })

  val canceled: IO[Unit] = Canceled

  val cede: IO[Unit] = Cede

  def uncancelable[A](body: IO ~> IO => IO[A]): IO[A] =
    Uncancelable(body)

  val executionContext: IO[ExecutionContext] = ReadEC

  val never: IO[Nothing] = async(_ => pure(None))

  val monotonic: IO[FiniteDuration] = Monotonic

  val realTime: IO[FiniteDuration] = RealTime

  def sleep(delay: FiniteDuration): IO[Unit] =
    Sleep(delay)

  def toK[F[_]: Effect]: IO ~> F =
    new (IO ~> F) {
      def apply[A](ioa: IO[A]) = ioa.to[F]
    }

  implicit def monoidForIO[A: Monoid]: Monoid[IO[A]] =
    new IOMonoid[A]

  protected class IOMonoid[A](override implicit val A: Monoid[A]) extends IOSemigroup[A] with Monoid[IO[A]] {
    def empty = IO.pure(A.empty)
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

    override def onCase[A](ioa: IO[A])(pf: PartialFunction[Outcome[IO, Throwable, A], IO[Unit]]): IO[A] =
      ioa.onCase(pf)

    def bracketCase[A, B](acquire: IO[A])(use: A => IO[B])(release: (A, Outcome[IO, Throwable, B]) => IO[Unit]): IO[B] =
      acquire.bracketCase(use)(release)

    val monotonic: IO[FiniteDuration] = IO.monotonic

    val realTime: IO[FiniteDuration] = IO.realTime

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

    override def map[A, B](fa: IO[A])(f: A => B): IO[B] =
      fa.map(f)

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
  private[effect] case object RealTime extends IO[FiniteDuration](15)
  private[effect] case object Monotonic extends IO[FiniteDuration](16)

  private[effect] case object Cede extends IO[Unit](17)

  // INTERNAL
  private[effect] final case class Unmask[+A](ioa: IO[A], id: AnyRef) extends IO[A](18)
}
