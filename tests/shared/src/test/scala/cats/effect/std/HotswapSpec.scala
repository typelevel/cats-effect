/*
 * Copyright 2020-2023 Typelevel
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

import scala.concurrent.duration._

class HotswapSpec extends BaseSpec { outer =>

  sequential

  def logged(log: Ref[IO, List[String]], name: String): Resource[IO, Unit] =
    Resource.make(log.update(_ :+ s"open $name"))(_ => log.update(_ :+ s"close $name"))

  "Hotswap" should {
    "run finalizer of target run when hotswap is finalized" in real {
      val op = for {
        log <- Ref.of[IO, List[String]](List())
        _ <- Hotswap[IO, Unit](logged(log, "a")).use(_ => IO.unit)
        value <- log.get
      } yield value

      op.flatMap { res =>
        IO {
          res must beEqualTo(List("open a", "close a"))
        }
      }
    }

    "acquire new resource and finalize old resource on swap" in real {
      val op = for {
        log <- Ref.of[IO, List[String]](List())
        _ <- Hotswap[IO, Unit](logged(log, "a")).use {
          case (hotswap, _) =>
            hotswap.swap(logged(log, "b"))
        }
        value <- log.get
      } yield value

      op.flatMap { res =>
        IO {
          res must beEqualTo(List("open a", "open b", "close a", "close b"))
        }
      }
    }

    "finalize old resource on clear" in real {
      val op = for {
        log <- Ref.of[IO, List[String]](List())
        _ <- Hotswap[IO, Unit](logged(log, "a")).use {
          case (hotswap, _) =>
            hotswap.clear *> hotswap.swap(logged(log, "b"))
        }
        value <- log.get
      } yield value

      op.flatMap { res =>
        IO {
          res must beEqualTo(List("open a", "close a", "open b", "close b"))
        }
      }
    }

    "not release current resource while it is in use" in ticked { implicit ticker =>
      val r = Resource.make(IO.ref(true))(_.set(false))
      val go = Hotswap.create[IO, Ref[IO, Boolean]].use { hs =>
        hs.swap(r) *> (IO.sleep(1.second) *> hs.clear).background.surround {
          hs.get.use {
            case Some(ref) =>
              val notReleased = ref.get.flatMap(b => IO(b must beTrue))
              notReleased *> IO.sleep(2.seconds) *> notReleased.void
            case None => IO(false must beTrue).void
          }
        }
      }

      go must completeAs(())
    }

    "resource can be accessed concurrently" in ticked { implicit ticker =>
      val go = Hotswap.create[IO, Unit].use { hs =>
        hs.swap(Resource.unit) *>
          hs.get.useForever.background.surround {
            IO.sleep(1.second) *> hs.get.use_
          }
      }

      go must completeAs(())
    }
  }

}
