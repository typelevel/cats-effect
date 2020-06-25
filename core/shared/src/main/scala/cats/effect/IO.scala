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
    IO.executionContext flatMap { ec =>
      IO {
        val fiber = new IOFiber(
          this,
          s"child-${IO.childCount.getAndIncrement()}")

        ec.execute(() => fiber.run(ec))
        fiber
      }
    }

  def onCase(pf: PartialFunction[Outcome[IO, Throwable, A @uncheckedVariance], IO[Unit]]): IO[A] = ???

  def onCancel(body: IO[Unit]): IO[A] =
    onCase { case Outcome.Canceled() => body }

  def to[F[_]: AsyncBracket]: F[A @uncheckedVariance] = ???

  def unsafeRunAsync(ec: ExecutionContext)(cb: Either[Throwable, A] => Unit): Unit = {
    new IOFiber(
      this,
      oc => oc.fold(
        (),
        e => cb(Left(e)),
        ioa => cb(Right(ioa.asInstanceOf[IO.Pure[A]].value)))).run(ec)
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

  private val childCount = new AtomicInteger(0)

  def pure[A](value: A): IO[A] = Pure(value)

  def unit: IO[Unit] = pure(())

  def apply[A](thunk: => A): IO[A] = Delay(() => thunk)

  def raiseError(t: Throwable): IO[Nothing] = Error(t)

  def async[A](k: (Either[Throwable, A] => Unit) => IO[Option[IO[Unit]]]): IO[A] = Async(k)

  def canceled: IO[Unit] = ???

  // in theory we can probably do a bit better than this; being lazy for now
  def cede: IO[Unit] =
    async(k => apply(k(Right(()))).map(_ => None))

  def uncancelable[A](body: IO ~> IO => IO[A]): IO[A] = ???

  val executionContext: IO[ExecutionContext] = IO.ReadEC

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

    def sleep(time: FiniteDuration): IO[Unit] = ???

    def canceled: IO[Unit] =
      IO.canceled

    def cede: IO[Unit] = IO.cede

    def racePair[A, B](fa: IO[A], fb: IO[B]): IO[Either[(A, Fiber[IO, Throwable, B]), (Fiber[IO, Throwable, A], B)]] = ???

    def start[A](fa: IO[A]): IO[Fiber[IO, Throwable, A]] =
      fa.start

    def uncancelable[A](body: IO ~> IO => IO[A]): IO[A] =
      IO.uncancelable(body)

    def toK[G[_]](implicit G: AsyncBracket[G]): IO ~> G =
      new (IO ~> G) {
        def apply[A](ioa: IO[A]) = ioa.to[G]
      }

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
}
