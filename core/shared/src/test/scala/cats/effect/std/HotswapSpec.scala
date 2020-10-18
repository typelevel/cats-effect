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

package cats
package effect
package std

import cats.effect.kernel.Ref

class HotswapSpec extends BaseSpec { outer =>

  sequential

  def log(state: Ref[IO, List[String]], message: String): IO[Unit] =
    state.update(_ :: message)

  def resource(state: Ref[IO, List[String]], name: String): IO[Unit] =
    Resource.make(log(state, s"open $name"))(_ => log(state, s"close $name"))

  "Hotswap" should {
    "run finalizer of target run when hotswap is finalized" in real {
      val op = for {
        state <- Ref.of[IO, Int](0)
        _ <- Hotswap[IO, Unit](Resource.make(IO.unit)(_ => state.set(1))).use(_ => IO.unit)
        value <- state.get
      } yield value

      op.flatMap { res =>
        IO {
          res must beEqualTo(1)
        }
      }
    }

    "acquire new resource and finalize old resource on swap" in real {
      val op = for {
        state <- Ref.of[IO, List[String]](0)
        _ <- Hotswap[IO, Unit](resource(state, "a")).use { hotswap =>
          hotswap.swap(resource(state, "b"))
        }
        log <- state.get
      } yield value

      op.flatMap { res =>
        IO {
          res must beEqualTo(List("open a", "open b", "close a", "close b"))
        }
      }
    }

    "finalize old resource on clear" in real {
      val op = for {
        state <- Ref.of[IO, List[String]](0)
        _ <- Hotswap[IO, Unit](resource(state, "a")).use { hotswap =>
          hotswap.clear *> hotswap.swap(resource(state, "b"))
        }
        log <- state.get
      } yield value

      op.flatMap { res =>
        IO {
          res must beEqualTo(List("open a", "close a", "open b", "close b"))
        }
      }
    }
  }

}
