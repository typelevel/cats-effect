/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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
import cats.effect.internals.Conversions
import cats.effect.laws.util.TestContext
import org.scalacheck.{Arbitrary, Cogen}
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.concurrent.{ExecutionContext, ExecutionException, Future, Promise}
import scala.util.{Either, Left, Right}

/**
 * Built for testing noncancelable effect data types.
 */
case class LTask[A](run: ExecutionContext => Future[A])

object LTask {

  /** For testing laws with ScalaCheck. */
  implicit def arbitrary[A](implicit A: Arbitrary[IO[A]]): Arbitrary[LTask[A]] =
    Arbitrary(A.arbitrary.map { io =>
      LTask(_ => io.unsafeToFuture())
    })

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
        LTask { implicit ec =>
          Future.successful(()).flatMap(_ => thunk.run(ec))
        }

      def async[A](k: (Either[Throwable, A] => Unit) => Unit): LTask[A] =
        LTask { _ =>
          val p = Promise[A]()
          k(r => p.tryComplete(Conversions.toTry(r)))
          p.future
        }

      def asyncF[A](k: (Either[Throwable, A] => Unit) => LTask[Unit]): LTask[A] =
        LTask { implicit ec =>
          val p = Promise[A]()
          k(r => p.tryComplete(Conversions.toTry(r))).run(ec)
          p.future
        }

      def runAsync[A](fa: LTask[A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit] =
        SyncIO(fa.run(ec).onComplete { r =>
          cb(Conversions.toEither(r)).unsafeRunAsyncAndForget()
        })

      def flatMap[A, B](fa: LTask[A])(f: A => LTask[B]): LTask[B] =
        LTask { implicit ec =>
          Future.successful(()).flatMap { _ =>
            fa.run(ec).flatMap { a =>
              f(a).run(ec)
            }
          }
        }

      def tailRecM[A, B](a: A)(f: A => LTask[Either[A, B]]): LTask[B] =
        flatMap(f(a)) {
          case Left(a)  => tailRecM(a)(f)
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

      def bracketCase[A, B](
        acquire: LTask[A]
      )(use: A => LTask[B])(release: (A, ExitCase[Throwable]) => LTask[Unit]): LTask[B] =
        for {
          a <- acquire
          etb <- attempt(use(a))
          _ <- release(a, etb match {
            case Left(e)  => ExitCase.error[Throwable](e)
            case Right(_) => ExitCase.complete
          })
          b <- rethrow(pure(etb))
        } yield b
    }
}
