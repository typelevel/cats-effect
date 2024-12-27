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

import scala.concurrent.duration._

class HotswapSuite extends BaseSuite { outer =>

  def logged(log: Ref[IO, List[String]], name: String): Resource[IO, Unit] =
    Resource.make(log.update(_ :+ s"open $name"))(_ => log.update(_ :+ s"close $name"))

  real("run finalizer of target run when hotswap is finalized") {
    val op = for {
      log <- Ref.of[IO, List[String]](List())
      _ <- Hotswap[IO, Unit](logged(log, "a")).use(_ => IO.unit)
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
      _ <- Hotswap[IO, Unit](logged(log, "a")).use {
        case (hotswap, _) =>
          hotswap.swap(logged(log, "b"))
      }
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
      _ <- Hotswap[IO, Unit](logged(log, "a")).use {
        case (hotswap, _) =>
          hotswap.clear *> hotswap.swap(logged(log, "b"))
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
    val r = Resource.make(IO.ref(true))(_.set(false))
    val go = Hotswap.create[IO, Ref[IO, Boolean]].use { hs =>
      hs.swap(r) *> (IO.sleep(1.second) *> hs.clear).background.surround {
        hs.get.use {
          case Some(ref) =>
            val notReleased = ref.get.flatMap(b => IO(assert(b)))
            notReleased *> IO.sleep(2.seconds) *> notReleased.void
          case None => IO(assert(false)).void
        }
      }
    }

    assertCompleteAs(go, ())
  }

  ticked("not finalize Hotswap while resource is in use") { implicit ticker =>
    val r = Resource.make(IO.ref(true))(_.set(false))
    val go = Hotswap.create[IO, Ref[IO, Boolean]].allocated.flatMap {
      case (hs, fin) =>
        hs.swap(r) *> (IO.sleep(1.second) *> fin).background.surround {
          hs.get.use {
            case Some(ref) =>
              val notReleased = ref.get.flatMap(b => IO(assert(b)))
              notReleased *> IO.sleep(2.seconds) *> notReleased.void
            case None => IO(assert(false)).void
          }
        }
    }

    assertCompleteAs(go, ())
  }

  ticked("resource can be accessed concurrently") { implicit ticker =>
    val go = Hotswap.create[IO, Unit].use { hs =>
      hs.swap(Resource.unit) *>
        hs.get.useForever.background.surround {
          IO.sleep(1.second) *> hs.get.use_
        }
    }

    assertCompleteAs(go, ())
  }

  ticked("not block current resource while swap is instantiating new one") { implicit ticker =>
    val go = Hotswap.create[IO, Unit].use { hs =>
      hs.swap(IO.sleep(1.minute).toResource).start *>
        IO.sleep(5.seconds) *>
        hs.get.use_.timeout(1.second).void
    }
    assertCompleteAs(go, ())
  }

  ticked(
    "successfully cancel during swap and run finalizer if cancelation is requested while waiting for get to release") {
    implicit ticker =>
      val go = Ref.of[IO, List[String]](List()).flatMap { log =>
        Hotswap[IO, Unit](logged(log, "a")).use {
          case (hs, _) =>
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

  ticked("swap is safe to concurrent cancelation") { implicit ticker =>
    val go = IO.ref(false).flatMap { open =>
      Hotswap[IO, Unit](Resource.unit)
        .use {
          case (hs, _) =>
            hs.swap(
              Resource.make(open.set(true))(_ => open.getAndSet(false).map(assert(_)).void))
        }
        .race(IO.unit) *> open.get.map(r => assert(!r))
    }

    assertCompleteAs(TestControl.executeEmbed(go, IORuntimeConfig(1, 2)).replicateA_(1000), ())
  }

  ticked("get should not acquire a lock when there is no resource present") { implicit ticker =>
    val go = Hotswap.create[IO, Unit].use { hs =>
      hs.get.useForever.start *>
        IO.sleep(2.seconds) *>
        hs.swap(Resource.unit)
    }
    assertCompleteAs(go, ())
  }
}
