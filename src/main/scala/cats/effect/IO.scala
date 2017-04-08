/*
 * Copyright 2017 Daniel Spiewak
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

package cats
package effect

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.{Left, Right}
import scala.util.control.NonFatal

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

sealed trait IO[+A] {
  import IO._

  final def map[B](f: A => B): IO[B] = this match {
    case Pure(a) => try Pure(f(a)) catch { case NonFatal(t) => Fail(t) }
    case Fail(t) => Fail(t)
    case _ => flatMap(f.andThen(Pure(_)))
  }

  final def flatMap[B](f: A => IO[B]): IO[B] = this match {
    case Pure(a) => Suspend(() => f(a))
    case Fail(t) => Fail(t)
    case Suspend(thunk) => BindSuspend(thunk, f)
    case BindSuspend(thunk, g) => BindSuspend(thunk, g.andThen(_.flatMap(f)))
    case Async(k) => BindAsync(k, f)
    case BindAsync(k, g) => BindAsync(k, g.andThen(_.flatMap(f)))
  }

  def attempt: IO[Attempt[A]]

  @tailrec
  private final def unsafeStep: IO[A] = this match {
    case Suspend(thunk) => thunk().unsafeStep
    case BindSuspend(thunk, f) => thunk().flatMap(f).unsafeStep
    case _ => this
  }

  final def unsafeRunSync(): A = unsafeRunTimed(Duration.Inf)

  final def unsafeRunAsync(cb: Attempt[A] => Unit): Unit = unsafeStep match {
    case Pure(a) => cb(Right(a))
    case Fail(t) => cb(Left(t))
    case Async(k) => k(cb)
    case BindAsync(k, f) => k {
      case Left(t) => cb(Left(t))
      case Right(a) => try f(a).unsafeRunAsync(cb) catch { case NonFatal(t) => cb(Left(t)) }
    }
    case _ => throw new AssertionError("unreachable")
  }

  final def unsafeRunTimed(limit: Duration): A = unsafeStep match {
    case Pure(a) => a
    case Fail(t) => throw t

    case self @ (Async(_) | BindAsync(_, _)) => {
      val latch = new CountDownLatch(1)
      val ref = new AtomicReference[Either[Throwable, A]](null)

      self unsafeRunAsync { e =>
        ref.set(e)
        latch.countDown()
      }

      if (limit == Duration.Inf)
        latch.await()
      else
        latch.await(limit.toMillis, TimeUnit.MILLISECONDS)

      ref.get().fold(throw _, a => a)
    }

    case _ => throw new AssertionError("unreachable")
  }
}

private[effect] trait IOInstances {

  implicit val ioMonad: Monad[IO] = new Monad[IO] with Sync[IO] with Async[IO] {

    def pure[A](a: A) = IO.pure(a)

    def flatMap[A, B](ioa: IO[A])(f: A => IO[B]): IO[B] = ioa.flatMap(f)

    // TODO uh... do better
    def tailRecM[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] = f(a) flatMap {
      case Left(a) => tailRecM(a)(f)
      case Right(b) => pure(b)
    }

    def attempt[A](ioa: IO[A]): IO[Attempt[A]] = ioa.attempt

    def fail[A](t: Throwable): IO[A] = IO.fail(t)

    def suspend[A](thunk: => IO[A]): IO[A] = IO.suspend(thunk)

    def async[A](k: (Attempt[A] => Unit) => Unit): IO[A] = IO.async(k)
  }
}

object IO extends IOInstances {

  def apply[A](body: => A): IO[A] = suspend(Pure(body))

  def suspend[A](thunk: => IO[A]): IO[A] = Suspend(() => thunk)

  def pure[A](a: A): IO[A] = Pure(a)

  def eval[A](effect: Eval[A]): IO[A] = effect match {
    case Now(a) => pure(a)
    case effect => apply(effect.value)
  }

  def async[A](k: (Attempt[A] => Unit) => Unit): IO[A] = Async(k)

  def fail(t: Throwable): IO[Nothing] = Fail(t)

  final case class Pure[+A](a: A) extends IO[A] {
    def attempt = Pure(Right(a))
  }

  final case class Fail(t: Throwable) extends IO[Nothing] {
    def attempt = Pure(Left(t))
  }

  final case class Suspend[+A](thunk: () => IO[A]) extends IO[A] {
    def attempt = Suspend(() => try thunk().attempt catch { case NonFatal(t) => Pure(Left(t)) })
  }

  final case class BindSuspend[E, +A](thunk: () => IO[E], f: E => IO[A]) extends IO[A] {
    def attempt: BindSuspend[Attempt[E], Attempt[A]] = {
      BindSuspend(
        () => try thunk().attempt catch { case NonFatal(t) => Pure(Left(t)) },
        _.fold(t => Pure(Left(t)), a => f(a).attempt))
    }
  }

  final case class Async[+A](k: (Attempt[A] => Unit) => Unit) extends IO[A] {
    def attempt = Async(cb => k(attempt => cb(Right(attempt))))
  }

  final case class BindAsync[E, +A](k: (Attempt[E] => Unit) => Unit, f: E => IO[A]) extends IO[A] {
    def attempt: BindAsync[Attempt[E], Attempt[A]] = {
      BindAsync(
        cb => k(attempt => cb(Right(attempt))),
        _.fold(t => Pure(Left(t)), a => f(a).attempt))
    }
  }
}
