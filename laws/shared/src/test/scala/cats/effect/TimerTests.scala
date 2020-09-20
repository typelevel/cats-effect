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

import cats.FlatMap
import cats.data._
import cats.syntax.all._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class TimerTests extends CatsEffectSuite {
  implicit val executionContext: ExecutionContext =
    ExecutionContext.global
  implicit val timer: Timer[IO] =
    IO.timer(executionContext)

  type EitherTIO[A] = EitherT[IO, Throwable, A]
  type OptionTIO[A] = OptionT[IO, A]
  type WriterTIO[A] = WriterT[IO, Int, A]
  type KleisliIO[A] = Kleisli[IO, Int, A]
  type StateTIO[A] = StateT[IO, Int, A]
  type IorTIO[A] = IorT[IO, Int, A]
  type ResourceIO[A] = Resource[IO, A]

  def resolveClock = {
    Clock[EitherTIO]
    Clock[OptionTIO]
    Clock[WriterTIO]
    Clock[KleisliIO]
    Clock[StateTIO]
    Clock[IorTIO]
    Clock[ResourceIO]
  }

  def tests[F[_]: Timer: FlatMap](label: String, run: F[Unit] => IO[Unit]): Unit = {
    test(s"Timer[$label].clock.realTime") {
      val time = System.currentTimeMillis()
      val test = for {
        t2 <- Timer[F].clock.realTime(MILLISECONDS)
      } yield assert(time > 0 && time <= t2)
      run(test)
    }

    test(s"Timer[$label].clock.monotonic") {
      val time = System.nanoTime()
      val test = for {
        t2 <- Timer[F].clock.monotonic(NANOSECONDS)
      } yield assert(time > 0 && time <= t2)
      run(test)
    }

    test(s"Timer[$label].sleep(100.ms)") {
      val t = Timer[F]
      val test = for {
        start <- t.clock.monotonic(MILLISECONDS)
        _ <- t.sleep(100.millis)
        end <- t.clock.monotonic(MILLISECONDS)
      } yield assert(end - start > 0L)
      run(test)
    }

    test(s"Timer[$label].sleep(negative)") {
      val test = for {
        _ <- Timer[F].sleep(-10.seconds)
      } yield assert(true)
      run(test)
    }
  }

  tests[IO]("IO", identity)
  tests[EitherTIO]("EitherT", _.value.as(()))
  tests[OptionTIO]("OptionT", _.value.as(()))
  tests[WriterTIO]("WriterT", _.run.as(()))
  tests[KleisliIO]("Kleisli", _.run(0))
  tests[StateTIO]("StateT", _.run(0).as(()))
  tests[IorTIO]("IorT", _.value.as(()))
  tests[ResourceIO]("Resource", _.use(_ => IO.unit))
}
