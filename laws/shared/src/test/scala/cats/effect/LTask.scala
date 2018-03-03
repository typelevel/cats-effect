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

import cats.Eq
import cats.effect.internals.{Callback, Conversions}
import cats.effect.laws.util.TestContext
import org.scalacheck.{Arbitrary, Cogen}
import scala.concurrent.{ExecutionContext, ExecutionException, Future, Promise}

/**
 * Built for testing non-cancelable effect data types.
 */
case class LTask[A](run: ExecutionContext => Future[A])

object LTask {
  /** For testing laws with ScalaCheck. */
  implicit def arbitrary[A](implicit A: Arbitrary[IO[A]]): Arbitrary[LTask[A]] =
    Arbitrary(A.arbitrary.map { io => LTask(_ => io.unsafeToFuture()) })

  /** For testing laws with ScalaCheck. */
  implicit def cogenForLTask[A]: Cogen[LTask[A]] =
    Cogen[Unit].contramap(_ => ())

  /** For testing laws with ScalaCheck. */
  implicit def eqForLTask[A](implicit A: Eq[Future[A]], ec: TestContext): Eq[LTask[A]] =
    new Eq[LTask[A]] {
      def eqv(x: LTask[A], y: LTask[A]): Boolean = {
        val lh = x.run(ec)
        val rh = y.run(ec)
        ec.tick()
        A.eqv(lh, rh)
      }
    }

  /** Instances for `LTask`. */
  implicit def effectInstance(implicit ec: ExecutionContext): Effect[LTask] =
    new Effect[LTask] {
      def pure[A](x: A): LTask[A] =
        LTask(_ => Future.successful(x))
      def raiseError[A](e: Throwable): LTask[A] =
        LTask(_ => Future.failed(e))
      def suspend[A](thunk: => LTask[A]): LTask[A] =
        LTask { implicit ec => Future.successful(()).flatMap(_ => thunk.run(ec)) }

      def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): LTask[A] =
        async { r => k(r); () }

      def async[A](k: (Either[Throwable, A] => Unit) => Unit): LTask[A] =
        LTask { implicit ec =>
          val p = Promise[A]()
          k(r => p.tryComplete(Conversions.toTry(r)))
          p.future
        }

      def runCancelable[A](fa: LTask[A])(cb: Either[Throwable, A] => IO[Unit]): IO[IO[Unit]] =
        runAsync(fa)(cb).map(_ => IO.unit)
      def runAsync[A](fa: LTask[A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] =
        IO(fa.run(ec).onComplete { r =>
          cb(Conversions.toEither(r)).unsafeRunAsync(Callback.report)
        })

      def start[A](fa: LTask[A]): LTask[Fiber[LTask, A]] =
        LTask { implicit ec =>
          Future {
            val f = fa.run(ec)
            new Fiber[LTask, A] {
              def join = LTask(_ => f)
              def cancel = LTask(_ => Future.successful(()))
            }
          }
        }

      def flatMap[A, B](fa: LTask[A])(f: A => LTask[B]): LTask[B] =
        LTask { implicit ec =>
          Future.successful(()).flatMap { _ =>
            fa.run(ec).flatMap { a => f(a).run(ec) }
          }
        }

      def tailRecM[A, B](a: A)(f: A => LTask[Either[A, B]]): LTask[B] =
        flatMap(f(a)) {
          case Left(a) => tailRecM(a)(f)
          case Right(b) => pure(b)
        }

      def handleErrorWith[A](fa: LTask[A])(f: Throwable => LTask[A]): LTask[A] =
        LTask { implicit ec =>
          Future.successful(()).flatMap { _ =>
            fa.run(ec).recoverWith {
              case err: ExecutionException if err.getCause ne null =>
                f(err.getCause).run(ec)
              case err =>
                f(err).run(ec)
            }
          }
        }
    }
}