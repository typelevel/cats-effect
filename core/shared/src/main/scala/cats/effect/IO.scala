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

import cats.{~>, Eval, Monoid, Now, Parallel, Semigroup, Show, StackSafeMonad}
import cats.implicits._
import cats.effect.implicits._

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

sealed abstract class IO[+A] private () extends IOPlatform[A] {

  private[effect] def tag: Byte

  def <*[B](that: IO[B]): IO[A] =
    productL(that)

  def *>[B](that: IO[B]): IO[B] =
    productR(that)

  def >>[B](that: => IO[B]): IO[B] =
    flatMap(_ => that)

  def as[B](b: B): IO[B] =
    map(_ => b)

  def attempt: IO[Either[Throwable, A]] =
    map(Right(_)).handleErrorWith(t => IO.pure(Left(t)))

  def both[B](that: IO[B]): IO[(A, B)] =
    racePair(that).flatMap {
      case Left((a, f)) => f.joinAndEmbedNever.map((a, _))
      case Right((f, b)) => f.joinAndEmbedNever.map((_, b))
    }

  def bracket[B](use: A => IO[B])(release: A => IO[Unit]): IO[B] =
    bracketCase(use)((a, _) => release(a))

  def bracketCase[B](use: A => IO[B])(
      release: (A, Outcome[IO, Throwable, B]) => IO[Unit]): IO[B] =
    IO uncancelable { poll =>
      flatMap { a =>
        val finalized = poll(use(a)).onCancel(release(a, Outcome.Canceled()))
        val handled = finalized onError {
          case e => release(a, Outcome.Errored(e)).attempt.void
        }
        handled.flatMap(b => release(a, Outcome.Completed(IO.pure(b))).attempt.as(b))
      }
    }

  def evalOn(ec: ExecutionContext): IO[A] = IO.EvalOn(this, ec)

  def flatMap[B](f: A => IO[B]): IO[B] = IO.FlatMap(this, f)

  def guarantee(finalizer: IO[Unit]): IO[A] =
    guaranteeCase(_ => finalizer)

  def guaranteeCase(
      finalizer: Outcome[IO, Throwable, A @uncheckedVariance] => IO[Unit]): IO[A] =
    onCase { case oc => finalizer(oc) }

  def handleErrorWith[B >: A](f: Throwable => IO[B]): IO[B] =
    IO.HandleErrorWith(this, f)

  def map[B](f: A => B): IO[B] = IO.Map(this, f)

  def onCancel(fin: IO[Unit]): IO[A] =
    IO.OnCancel(this, fin)

  def onCase(
      pf: PartialFunction[Outcome[IO, Throwable, A @uncheckedVariance], IO[Unit]]): IO[A] =
    IO uncancelable { poll =>
      val base = poll(this)
      val finalized = pf.lift(Outcome.Canceled()).map(base.onCancel(_)).getOrElse(base)

      finalized.attempt flatMap {
        case Left(e) =>
          pf.lift(Outcome.Errored(e)).map(_.attempt).getOrElse(IO.unit) *> IO.raiseError(e)

        case Right(a) =>
          pf.lift(Outcome.Completed(IO.pure(a))).map(_.attempt).getOrElse(IO.unit).as(a)
      }
    }

  def race[B](that: IO[B]): IO[Either[A, B]] =
    racePair(that).flatMap {
      case Left((a, f)) => f.cancel.as(Left(a))
      case Right((f, b)) => f.cancel.as(Right(b))
    }

  def racePair[B](that: IO[B]): IO[Either[
    (A @uncheckedVariance, Fiber[IO, Throwable, B]),
    (Fiber[IO, Throwable, A @uncheckedVariance], B)]] =
    IO.RacePair(this, that)

  def delayBy(duration: FiniteDuration): IO[A] =
    IO.sleep(duration) *> this

  def timeout[A2 >: A](duration: FiniteDuration): IO[A2] =
    timeoutTo(duration, IO.raiseError(new TimeoutException(duration.toString)))

  def timeoutTo[A2 >: A](duration: FiniteDuration, fallback: IO[A2]): IO[A2] =
    race(IO.sleep(duration)).flatMap {
      case Right(_) => fallback
      case Left(value) => IO.pure(value)
    }

  def productL[B](that: IO[B]): IO[A] =
    flatMap(a => that.as(a))

  def productR[B](that: IO[B]): IO[B] =
    flatMap(_ => that)

  def start: IO[Fiber[IO, Throwable, A @uncheckedVariance]] =
    IO.Start(this)

  def to[F[_]](implicit F: Effect[F]): F[A @uncheckedVariance] =
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

        case IO.OnCancel(ioa, fin) =>
          F.onCancel(ioa.to[F], fin.to[F]).asInstanceOf[F[A]]

        case IO.Uncancelable(body) =>
          F.uncancelable { poll =>
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
            e.bimap({ case (a, f) => (a, fiberFrom(f)) }, { case (f, b) => (fiberFrom(f), b) })
          }

          back.asInstanceOf[F[A]]

        case self: IO.Sleep => F.sleep(self.delay).asInstanceOf[F[A]]
        case _: IO.RealTime.type => F.realTime.asInstanceOf[F[A]]
        case _: IO.Monotonic.type => F.monotonic.asInstanceOf[F[A]]

        case _: IO.Cede.type => F.cede.asInstanceOf[F[A]]

        case IO.Unmask(ioa, _) => ioa.to[F] // polling should be handled by F
      }
    }

  def uncancelable: IO[A] =
    IO.uncancelable(_ => this)

  def void: IO[Unit] =
    map(_ => ())

  override def toString: String = "IO(...)"

  // unsafe stuff

  def unsafeRunAsync(cb: Either[Throwable, A] => Unit)(
      implicit runtime: unsafe.IORuntime): Unit = {
    unsafeRunFiber(true)(cb)
    ()
  }

  def unsafeToFuture()(implicit runtime: unsafe.IORuntime): Future[A] = {
    val p = Promise[A]()

    unsafeRunAsync {
      case Left(t) => p.failure(t)
      case Right(a) => p.success(a)
    }

    p.future
  }

  private[effect] def unsafeRunFiber(shift: Boolean)(cb: Either[Throwable, A] => Unit)(
      implicit runtime: unsafe.IORuntime): IOFiber[A @uncheckedVariance] = {

    val fiber = new IOFiber(
      runtime.scheduler,
      (oc: Outcome[IO, Throwable, A]) =>
        oc.fold((), e => cb(Left(e)), ioa => cb(Right(ioa.asInstanceOf[IO.Pure[A]].value))),
      0)

    if (shift)
      runtime.compute.execute(() => fiber.run(this, runtime.compute, 0))
    else
      fiber.run(this, runtime.compute, 0)

    fiber
  }
}

private[effect] trait IOLowPriorityImplicits {

  implicit def showForIONoPure[A]: Show[IO[A]] =
    Show.show(_ => "IO(...)")

  implicit def semigroupForIO[A: Semigroup]: Semigroup[IO[A]] =
    new IOSemigroup[A]

  protected class IOSemigroup[A](implicit val A: Semigroup[A]) extends Semigroup[IO[A]] {
    def combine(left: IO[A], right: IO[A]) =
      left.flatMap(l => right.map(r => l |+| r))
  }
}

object IO extends IOCompanionPlatform with IOLowPriorityImplicits {

  // constructors

  def apply[A](thunk: => A): IO[A] = Delay(() => thunk)

  def delay[A](thunk: => A): IO[A] = apply(thunk)

  def suspend[A](thunk: => IO[A]): IO[A] =
    delay(thunk).flatten

  def async[A](k: (Either[Throwable, A] => Unit) => IO[Option[IO[Unit]]]): IO[A] = Async(k)

  def async_[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A] =
    async(cb => apply { k(cb); None })

  def canceled: IO[Unit] = Canceled

  def cede: IO[Unit] = Cede

  def executionContext: IO[ExecutionContext] = ReadEC

  def monotonic: IO[FiniteDuration] = Monotonic

  private[this] val _never: IO[Nothing] = async(_ => pure(None))
  def never[A]: IO[A] = _never

  def pure[A](value: A): IO[A] = Pure(value)

  def raiseError[A](t: Throwable): IO[A] = Error(t)

  def realTime: IO[FiniteDuration] = RealTime

  def sleep(delay: FiniteDuration): IO[Unit] =
    Sleep(delay)

  def uncancelable[A](body: IO ~> IO => IO[A]): IO[A] =
    Uncancelable(body)

  private[this] val _unit: IO[Unit] = Pure(())
  def unit: IO[Unit] = _unit

  // utilities

  def both[A, B](left: IO[A], right: IO[B]): IO[(A, B)] =
    left.both(right)

  def fromFuture[A](fut: IO[Future[A]]): IO[A] =
    fut flatMap { f =>
      executionContext flatMap { implicit ec =>
        async_[A] { cb => f.onComplete(t => cb(t.toEither)) }
      }
    }

  def race[A, B](left: IO[A], right: IO[B]): IO[Either[A, B]] =
    left.race(right)

  def racePair[A, B](
      left: IO[A],
      right: IO[B]): IO[Either[(A, Fiber[IO, Throwable, B]), (Fiber[IO, Throwable, A], B)]] =
    left.racePair(right)

  def toK[F[_]: Effect]: IO ~> F =
    new (IO ~> F) {
      def apply[A](ioa: IO[A]) = ioa.to[F]
    }

  def eval[A](fa: Eval[A]): IO[A] =
    fa match {
      case Now(a) => pure(a)
      case notNow => apply(notNow.value)
    }

  def fromOption[A](o: Option[A])(orElse: => Throwable): IO[A] =
    o match {
      case None => raiseError(orElse)
      case Some(value) => pure(value)
    }

  def fromEither[A](e: Either[Throwable, A]): IO[A] =
    e match {
      case Right(a) => pure(a)
      case Left(err) => raiseError(err)
    }

  def fromTry[A](t: Try[A]): IO[A] =
    t match {
      case Success(a) => pure(a)
      case Failure(err) => raiseError(err)
    }

  // instances

  implicit def showForIO[A: Show]: Show[IO[A]] =
    Show.show {
      case Pure(a) => s"IO(${a.show})"
      case _ => "IO(...)"
    }

  implicit def monoidForIO[A: Monoid]: Monoid[IO[A]] =
    new IOMonoid[A]

  protected class IOMonoid[A](override implicit val A: Monoid[A])
      extends IOSemigroup[A]
      with Monoid[IO[A]] {
    def empty = IO.pure(A.empty)
  }

  implicit val effectForIO: Effect[IO] = new Effect[IO] with StackSafeMonad[IO] {

    override def as[A, B](ioa: IO[A], b: B): IO[B] =
      ioa.as(b)

    override def attempt[A](ioa: IO[A]) =
      ioa.attempt

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

    def onCancel[A](ioa: IO[A], fin: IO[Unit]): IO[A] =
      ioa.onCancel(fin)

    override def bracketCase[A, B](acquire: IO[A])(use: A => IO[B])(
        release: (A, Outcome[IO, Throwable, B]) => IO[Unit]): IO[B] =
      acquire.bracketCase(use)(release)

    val monotonic: IO[FiniteDuration] = IO.monotonic

    val realTime: IO[FiniteDuration] = IO.realTime

    def sleep(time: FiniteDuration): IO[Unit] =
      IO.sleep(time)

    def canceled: IO[Unit] =
      IO.canceled

    def cede: IO[Unit] = IO.cede

    def racePair[A, B](
        fa: IO[A],
        fb: IO[B]): IO[Either[(A, Fiber[IO, Throwable, B]), (Fiber[IO, Throwable, A], B)]] =
      fa.racePair(fb)

    override def race[A, B](fa: IO[A], fb: IO[B]): IO[Either[A, B]] =
      fa.race(fb)

    override def both[A, B](fa: IO[A], fb: IO[B]): IO[(A, B)] =
      fa.both(fb)

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

  implicit val parallelForIO: Parallel.Aux[IO, ParallelF[IO, *]] =
    parallelForConcurrent[IO, Throwable]

  // implementations

  final private[effect] case class Pure[+A](value: A) extends IO[A] {
    def tag = 0
    override def toString: String = s"IO($value)"
  }

  private[effect] final case class Delay[+A](thunk: () => A) extends IO[A] { def tag = 1 }
  private[effect] final case class Error(t: Throwable) extends IO[Nothing] { def tag = 2 }

  private[effect] final case class Async[+A](
      k: (Either[Throwable, A] => Unit) => IO[Option[IO[Unit]]])
      extends IO[A] {

    def tag = 3
  }

  private[effect] case object ReadEC extends IO[ExecutionContext] { def tag = 4 }

  private[effect] final case class EvalOn[+A](ioa: IO[A], ec: ExecutionContext) extends IO[A] {
    def tag = 5
  }

  private[effect] final case class Map[E, +A](ioe: IO[E], f: E => A) extends IO[A] {
    def tag = 6
  }

  private[effect] final case class FlatMap[E, +A](ioe: IO[E], f: E => IO[A]) extends IO[A] {
    def tag = 7
  }

  private[effect] final case class HandleErrorWith[+A](ioa: IO[A], f: Throwable => IO[A])
      extends IO[A] {
    def tag = 8
  }

  private[effect] final case class OnCancel[A](ioa: IO[A], fin: IO[Unit]) extends IO[A] {
    def tag = 9
  }

  private[effect] final case class Uncancelable[+A](body: IO ~> IO => IO[A]) extends IO[A] {
    def tag = 10
  }

  private[effect] case object Canceled extends IO[Unit] { def tag = 11 }

  private[effect] final case class Start[A](ioa: IO[A]) extends IO[Fiber[IO, Throwable, A]] {
    def tag = 12
  }
  private[effect] final case class RacePair[A, B](ioa: IO[A], iob: IO[B])
      extends IO[Either[(A, Fiber[IO, Throwable, B]), (Fiber[IO, Throwable, A], B)]] {

    def tag = 13
  }

  private[effect] final case class Sleep(delay: FiniteDuration) extends IO[Unit] {
    def tag = 14
  }

  private[effect] case object RealTime extends IO[FiniteDuration] { def tag = 15 }
  private[effect] case object Monotonic extends IO[FiniteDuration] { def tag = 16 }

  private[effect] case object Cede extends IO[Unit] { def tag = 17 }

  // INTERNAL
  private[effect] final case class Unmask[+A](ioa: IO[A], id: Int) extends IO[A] {
    def tag = 18
  }
}
