/*
 * Copyright 2017 Typelevel
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

package cats.effect.laws.util

import cats.effect.IO
import cats.effect.util.CompositeException
import cats.kernel.Eq

import scala.annotation.tailrec
import scala.concurrent.{ExecutionException, Future}
import scala.util.{Failure, Success}

/**
 * Defines instances for `Future` and for `IO`, meant for law testing
 * by means of [[TestContext]].
 *
 * The [[TestContext]] interpreter is used here for simulating
 * asynchronous execution.
 */
trait TestInstances {
  /**
   * Defines equality for `IO` references that can
   * get interpreted by means of a [[TestContext]].
   */
  implicit def eqIO[A](implicit A: Eq[A], ec: TestContext): Eq[IO[A]] =
    new Eq[IO[A]] {
      def eqv(x: IO[A], y: IO[A]): Boolean =
        eqFuture[A].eqv(x.unsafeToFuture(), y.unsafeToFuture())
    }

  /**
    * Defines equality for `IO.Par` references that can
    * get interpreted by means of a [[TestContext]].
    */
  implicit def eqIOPar[A](implicit A: Eq[A], ec: TestContext): Eq[IO.Par[A]] =
    new Eq[IO.Par[A]] {
      def eqv(x: IO.Par[A], y: IO.Par[A]): Boolean =
        eqFuture[A].eqv(x.toIO.unsafeToFuture(), y.toIO.unsafeToFuture())
    }

  /**
   * Defines equality for `Future` references that can
   * get interpreted by means of a [[TestContext]].
   */
  implicit def eqFuture[A](implicit A: Eq[A], ec: TestContext): Eq[Future[A]] =
    new Eq[Future[A]] {
      def eqv(x: Future[A], y: Future[A]): Boolean = {
        // Executes the whole pending queue of runnables
        ec.tick()

        x.value match {
          case None =>
            y.value.isEmpty
          case Some(Success(a)) =>
            y.value match {
              case Some(Success(b)) => A.eqv(a, b)
              case _ => false
            }
          case Some(Failure(ex1)) =>
            y.value match {
              case Some(Failure(ex2)) =>
                extractEx(ex1) == extractEx(ex2)
              case _ =>
                false
            }
        }
      }

      // Unwraps exceptions that got caught by Future's implementation
      // and that got wrapped in ExecutionException (`Future(throw ex)`)
      @tailrec def extractEx(ex: Throwable): String = {
        ex match {
          case e: ExecutionException if e.getCause != null =>
            extractEx(e.getCause)
          case e: CompositeException =>
            extractEx(e.head)
          case _ =>
            s"${ex.getClass.getName}: ${ex.getMessage}"
        }
      }
    }
}

object TestInstances extends TestInstances