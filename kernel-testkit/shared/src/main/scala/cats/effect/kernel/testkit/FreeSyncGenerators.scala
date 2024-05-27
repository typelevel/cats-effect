/*
 * Copyright 2020-2024 Typelevel
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
package kernel
package testkit

import cats.{Eval, Monad, MonadError}
import cats.effect.kernel.testkit.freeEval._
import cats.free.FreeT

import org.scalacheck.{Arbitrary, Cogen, Gen}

import scala.concurrent.duration.FiniteDuration

import java.util.concurrent.TimeUnit

object FreeSyncGenerators {

  implicit def cogenFreeSync[F[_]: Monad, A](
      implicit C: Cogen[F[A]]): Cogen[FreeT[Eval, F, A]] =
    C.contramap(run(_))

  def generators[F[_]](implicit F0: MonadError[F, Throwable]) =
    new SyncGenerators[FreeT[Eval, F, *]] {

      val arbitraryE: Arbitrary[Throwable] =
        Arbitrary(Arbitrary.arbitrary[Int].map(TestException(_)))

      val cogenE: Cogen[Throwable] =
        Cogen[Int].contramap(_.asInstanceOf[TestException].i)

      val arbitraryFD: Arbitrary[FiniteDuration] = {
        import TimeUnit._

        val genTU =
          Gen.oneOf(NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS)

        Arbitrary {
          genTU flatMap { u => Gen.posNum[Long].map(FiniteDuration(_, u)) }
        }
      }

      val F: Sync[FreeT[Eval, F, *]] =
        syncForFreeT[F]
    }

  implicit def arbitraryFreeSync[F[_], A: Arbitrary: Cogen](
      implicit F: MonadError[F, Throwable]): Arbitrary[FreeT[Eval, F, A]] =
    Arbitrary(generators[F].generators[A])
}

final case class TestException(i: Int) extends Exception
