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

package cats.effect

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration._

import java.nio.ByteBuffer
import java.nio.channels.Pipe
import java.nio.channels.SelectionKey._

class SelectorSuite extends BaseSuite {

  def mkPipe: Resource[IO, Pipe] =
    Resource
      .eval(Selector.get)
      .flatMap { selector =>
        Resource.make(IO(selector.provider.openPipe())) { pipe =>
          IO(pipe.sink().close()).guarantee(IO(pipe.source().close()))
        }
      }
      .evalTap { pipe =>
        IO {
          pipe.sink().configureBlocking(false)
          pipe.source().configureBlocking(false)
        }
      }

  real("notify read-ready events") {
    mkPipe.use { pipe =>
      for {
        selector <- Selector.get
        buf <- IO(ByteBuffer.allocate(4))
        _ <- IO(pipe.sink.write(ByteBuffer.wrap(Array(1, 2, 3)))).background.surround {
          selector.select(pipe.source, OP_READ) *> IO(pipe.source.read(buf))
        }
        _ <- IO(pipe.sink.write(ByteBuffer.wrap(Array(42)))).background.surround {
          selector.select(pipe.source, OP_READ) *> IO(pipe.source.read(buf))
        }
      } yield assertEquals(buf.array().toList, List[Byte](1, 2, 3, 42))
    }
  }

  real("setup multiple callbacks") {
    mkPipe.use { pipe =>
      for {
        selector <- Selector.get
        _ <- selector.select(pipe.source, OP_READ).parReplicateA_(10) <&
          IO(pipe.sink.write(ByteBuffer.wrap(Array(1, 2, 3))))
      } yield ()
    }
  }

  real("works after blocking") {
    mkPipe.use { pipe =>
      for {
        selector <- Selector.get
        _ <- IO.blocking(())
        _ <- selector.select(pipe.sink, OP_WRITE)
      } yield ()
    }
  }

  real("gracefully handles illegal ops") {
    mkPipe.use { pipe =>
      // get off the wstp to test async codepaths
      IO.blocking(()) *> Selector.get.flatMap { selector =>
        selector.select(pipe.sink, OP_READ).attempt.map {
          case Left(_: IllegalArgumentException) => true
          case _ => false
        }
      }
    }
  }

  testUnit("handles concurrent close") {
    val (pool, poller, shutdown) = IORuntime.createWorkStealingComputeThreadPool(threads = 1)
    implicit val runtime: IORuntime =
      IORuntime.builder().setCompute(pool, shutdown).addPoller(poller, () => ()).build()

    try {
      val test = Selector
          .get
        .flatMap { selector =>
          mkPipe.allocated.flatMap {
            case (pipe, close) =>
              selector.select(pipe.source, OP_READ).background.surround {
                IO.sleep(1.millis) *> close *> IO.sleep(1.millis)
              }
          }
        }
        .replicateA_(1000)
        .as(true)
      assert(test.unsafeRunSync())
    } finally {
      runtime.shutdown()
    }
  }

}
