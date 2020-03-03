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

package cats.effect.laws.util

import cats.effect.{Bracket, IO, Resource, SyncIO}
import cats.kernel.Eq
import scala.concurrent.Future
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
      import IO.Par.unwrap
      def eqv(x: IO.Par[A], y: IO.Par[A]): Boolean =
        eqFuture[A].eqv(unwrap(x).unsafeToFuture(), unwrap(y).unsafeToFuture())
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
              case _                => false
            }
          case Some(Failure(ex)) =>
            y.value match {
              case Some(Failure(ey)) => eqThrowable.eqv(ex, ey)
              case _                 => false
            }
        }
      }
    }

  implicit val eqThrowable: Eq[Throwable] =
    new Eq[Throwable] {
      def eqv(x: Throwable, y: Throwable): Boolean =
        // All exceptions are non-terminating and given exceptions
        // aren't values (being mutable, they implement reference
        // equality), then we can't really test them reliably,
        // especially due to race conditions or outside logic
        // that wraps them (e.g. ExecutionException)
        (x ne null) == (y ne null)
    }

  /**
   * Defines equality for a `Resource`.  Two resources are deemed
   * equivalent if they allocate an equivalent resource.  Cleanup,
   * which is run purely for effect, is not considered.
   */
  implicit def eqResource[F[_], A](implicit E: Eq[F[A]], F: Bracket[F, Throwable]): Eq[Resource[F, A]] =
    new Eq[Resource[F, A]] {
      def eqv(x: Resource[F, A], y: Resource[F, A]): Boolean =
        E.eqv(x.use(F.pure), y.use(F.pure))
    }

  /**
   * Defines equality for `Resource.Par`.  Two resources are deemed
   * equivalent if they allocate an equivalent resource.  Cleanup,
   * which is run purely for effect, is not considered.
   */
  implicit def eqResourcePar[F[_], A](implicit E: Eq[Resource[F, A]]): Eq[Resource.Par[F, A]] =
    new Eq[Resource.Par[F, A]] {
      import Resource.Par.unwrap
      def eqv(x: Resource.Par[F, A], y: Resource.Par[F, A]): Boolean =
        E.eqv(unwrap(x), unwrap(y))
    }

  /** Defines equality for `SyncIO` references. */
  implicit def eqSyncIO[A](implicit A: Eq[A]): Eq[SyncIO[A]] =
    new Eq[SyncIO[A]] {
      def eqv(x: SyncIO[A], y: SyncIO[A]): Boolean = {
        val eqETA = cats.kernel.instances.either.catsStdEqForEither(eqThrowable, A)
        eqETA.eqv(x.attempt.unsafeRunSync(), y.attempt.unsafeRunSync())
      }
    }
}

object TestInstances extends TestInstances
