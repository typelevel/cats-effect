/*
 * Copyright 2020-2025 Typelevel
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

import cats.effect.Resource
import cats.effect.kernel.Ref
import cats.effect.testkit.TestControl
import cats.effect.unsafe.IORuntimeConfig
import cats.syntax.all._

import scala.concurrent.duration._

class NonEmptyHotswapSuite extends BaseSuite {
  outer =>
  def logged(log: Ref[IO, List[String]], name: String): Resource[IO, Unit] =
    Resource.make(log.update(_ :+ s"open $name"))(_ => log.update(_ :+ s"close $name"))

  real("run finalizer of target run when NonEmptyHotSwap is finalized") {
    val op = for {
      log <- Ref.of[IO, List[String]](List())
      _ <- NonEmptyHotswap[IO, Unit](logged(log, "a")).use(_ => IO.unit)
      value <- log.get
    } yield value

    op.flatMap { res =>
      IO {
        assertEquals(res, List("open a", "close a"))
      }
    }
  }

  real("acquire new resource and finalize old resource on swap") {
    val op = for {
      log <- Ref.of[IO, List[String]](List())
      _ <-
        NonEmptyHotswap[IO, Unit](logged(log, "a")).use(_.swap(logged(log, "b")))
      value <- log.get
    } yield value

    op.flatMap { res =>
      IO {
        assertEquals(res, List("open a", "open b", "close a", "close b"))
      }
    }
  }

  real("finalize old resource on clear") {
    val op = for {
      log <- Ref.of[IO, List[String]](List())
      _ <- NonEmptyHotswap[IO, Option[Unit]](logged(log, "a").map(_.some)).use { hotswap =>
        hotswap.clear *> hotswap.swap(logged(log, "b").map(_.some))
      }
      value <- log.get
    } yield value

    op.flatMap { res =>
      IO {
        assertEquals(res, List("open a", "close a", "open b", "close b"))
      }
    }
  }

  ticked("not release current resource while it is in use") { implicit ticker =>
    val r0 = Resource.make(IO.ref(1))(_.set(10))
    val r1 = Resource.make(IO.ref(2))(_.set(20))
    val r2 = Resource.make(IO.ref(3))(_.set(30))
    val go = NonEmptyHotswap[IO, Ref[IO, Int]](r0).use { hs =>
      hs.swap(r1) *> (IO.sleep(1.second) *> hs.swap(r2)).background.surround {
        hs.get.use { ref =>
          val notReleased = ref.get.flatMap(b => IO(assertEquals(b, 2)))
          notReleased *> IO.sleep(2.seconds) *> notReleased.void
        }
      }
    }

    assertCompleteAs(go, ())
  }

  ticked("not finalize NonEmptyHotSwap while resource is in use") { implicit ticker =>
    val r0 = Resource.make(IO.ref(1))(_.set(10))
    val r1 = Resource.make(IO.ref(2))(_.set(20))
    val go = NonEmptyHotswap[IO, Ref[IO, Int]](r0).allocated.flatMap {
      case (hs, fin) =>
        hs.swap(r1) *> (IO.sleep(1.second) *> fin).background.surround {
          hs.get.use { ref =>
            val notReleased = ref.get.flatMap(b => IO(assertEquals(b, 2)))
            notReleased *> IO.sleep(2.seconds) *> notReleased.void
          }
        }
    }

    assertCompleteAs(go, ())
  }

  ticked("resource can be accessed concurrently") { implicit ticker =>
    val go = NonEmptyHotswap[IO, Unit](Resource.unit).use { hs =>
      hs.get.useForever.background.surround {
        IO.sleep(1.second) *> hs.get.use_
      }
    }

    assertCompleteAs(go, ())
  }

  ticked("not block current resource while swap is instantiating new one") { implicit ticker =>
    val go = NonEmptyHotswap[IO, Unit](Resource.unit).use { hs =>
      hs.swap(IO.sleep(1.minute).toResource).start *>
        IO.sleep(5.seconds) *>
        hs.get.use_.timeout(1.second).void
    }
    assertCompleteAs(go, ())
  }

  ticked(
    "successfully cancel during swap and run finalizer if cancellation is requested while waiting for get to release") {
    implicit ticker =>
      val go = Ref.of[IO, List[String]](List()).flatMap { log =>
        NonEmptyHotswap[IO, Unit](logged(log, "a")).use { hs =>
          for {
            _ <- hs.get.evalMap(_ => IO.sleep(1.minute)).use_.start
            _ <- IO.sleep(2.seconds)
            _ <- hs.swap(logged(log, "b")).timeoutTo(1.second, IO.unit)
            value <- log.get
          } yield value
        }
      }

      assertCompleteAs(go, List("open a", "open b", "close b"))
  }

  ticked("swap is safe to concurrent cancellation") { implicit ticker =>
    val go = IO.ref(false).flatMap { open =>
      NonEmptyHotswap[IO, Unit](Resource.unit)
        .use { hs =>
          hs.swap(Resource.make(open.set(true))(_ =>
            open.getAndSet(false).map(assertEquals(_, true)).void))
        }
        .race(IO.unit) *> open.get.map(assertEquals(_, false))
    }

    assertCompleteAs(TestControl.executeEmbed(go, IORuntimeConfig(1, 2)).replicateA_(1000), ())
  }

  ticked("getOpt should not acquire a lock when there is no resource present") {
    implicit ticker =>
      val go = NonEmptyHotswap.empty[IO, Unit].use { hs =>
        hs.getOpt.useForever.start *>
          IO.sleep(2.seconds) *>
          hs.swap(Resource.unit.map(_.some))
      }
      assertCompleteAs(go, ())
  }
}
