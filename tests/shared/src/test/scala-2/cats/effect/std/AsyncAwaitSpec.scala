/*
 * Copyright 2020-2021 Typelevel
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
package std

import scala.concurrent.duration._
import cats.syntax.all._
import cats.data.Kleisli
import cats.data.OptionT
import cats.data.WriterT

class AsyncAwaitSpec extends BaseSpec {

  "IOAsyncAwait" should {
    object IOAsyncAwait extends cats.effect.std.AsyncAwaitDsl[IO]
    import IOAsyncAwait.{await => ioAwait, _}

    "work on success" in real {

      val io = IO.sleep(100.millis) >> IO.pure(1)

      val program = async(ioAwait(io) + ioAwait(io))

      program.flatMap { res =>
        IO {
          res must beEqualTo(2)
        }
      }
    }

    "propagate errors outward" in real {

      case object Boom extends Throwable
      val io = IO.raiseError[Int](Boom)

      val program = async(ioAwait(io))

      program.attempt.flatMap { res =>
        IO {
          res must beEqualTo(Left(Boom))
        }
      }
    }

    "propagate uncaught errors outward" in real {

      case object Boom extends Throwable

      val program = async(throw Boom)

      program.attempt.flatMap { res =>
        IO {
          res must beEqualTo(Left(Boom))
        }
      }
    }

    "propagate canceled outcomes outward" in real {

      val io = IO.canceled

      val program = async(ioAwait(io))

      program.start.flatMap(_.join).flatMap { res =>
        IO {
          res must beEqualTo(Outcome.canceled[IO, Throwable, Unit])
        }
      }
    }

    "be cancellable" in real {

      val program = for {
        ref <- Ref[IO].of(0)
        _ <- async {
          ioAwait(IO.never)
          ioAwait(ref.update(_ + 1))
        }.start.flatMap(_.cancel)
        result <- ref.get
      } yield {
        result
      }

      program.flatMap { res =>
        IO {
          res must beEqualTo(0)
        }
      }

    }

    "suspend side effects" in real {
      var x = 0
      val program = async(x += 1)

      for {
        before <- IO(x must beEqualTo(0))
        _ <- program
        _ <- IO(x must beEqualTo(1))
        _ <- program
        _ <- IO(x must beEqualTo(2))
      } yield ok
    }
  }

  "KleisliAsyncAwait" should {
    type F[A] = Kleisli[IO, Int, A]
    object KleisliAsyncAwait extends cats.effect.std.AsyncAwaitDsl[F]
    import KleisliAsyncAwait.{await => kAwait, _}

    "work on successes" in real {
      val io = Temporal[F].sleep(100.millis) >> Kleisli(x => IO.pure(x + 1))

      val program = async(kAwait(io) + kAwait(io))

      program.run(0).flatMap { res =>
        IO {
          res must beEqualTo(2)
        }
      }
    }
  }

  "OptionTAsyncAwait" should {
    type F[A] = OptionT[IO, A]
    object OptionTAsyncAwait extends cats.effect.std.AsyncAwaitDsl[F]
    import OptionTAsyncAwait.{await => oAwait, _}

    "work on successes" in real {
      val io = Temporal[F].sleep(100.millis) >> OptionT.pure[IO](1)

      val program = async(oAwait(io) + oAwait(io))

      program.value.flatMap { res =>
        IO {
          res must beEqualTo(Some(2))
        }
      }
    }

    "work on None" in real {
      val io1 = OptionT.pure[IO](1)
      val io2 = OptionT.none[IO, Int]

      val program = async(oAwait(io1) + oAwait(io2))

      program.value.flatMap { res =>
        IO {
          res must beEqualTo(None)
        }
      }
    }
  }

  "Nested OptionT AsyncAwait" should {
    type F[A] = OptionT[OptionT[IO, *], A]
    object NestedAsyncAwait extends cats.effect.std.AsyncAwaitDsl[F]
    import NestedAsyncAwait.{await => oAwait, _}

    "surface None at the right layer (1)" in real {
      val io = OptionT.liftF(OptionT.none[IO, Int])

      val program = async(oAwait(io))

      program.value.value.flatMap { res =>
        IO {
          res must beEqualTo(None)
        }
      }
    }

    "surface None at the right layer (2)" in real {
      val io1 = 1.pure[F]
      val io2 = OptionT.none[OptionT[IO, *], Int]

      val program = async(oAwait(io1) + oAwait(io2))

      program.value.value.flatMap { res =>
        IO {
          res must beEqualTo(Some(None))
        }
      }
    }
  }

  "WriterT AsyncAwait" should {
    type F[A] = WriterT[IO, Int, A]
    object WriterTAsyncAwait extends cats.effect.std.AsyncAwaitDsl[F]
    import WriterTAsyncAwait.{await => wAwait, _}

    "surface logged " in real {
      val io1 = WriterT(IO((1, 3)))

      val program = async(wAwait(io1) * wAwait(io1))

      program.run.flatMap { res =>
        IO {
          res must beEqualTo((2, 9))
        }
      }
    }

  }

}
