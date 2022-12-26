/*
 * Copyright 2020-2022 Typelevel
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

import cats.syntax.all._

import java.nio.channels.Pipe
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey._

class SelectorPollerSpec extends BaseSpec {

  def mkPipe: Resource[IO, Pipe] =
    Resource
      .eval(IO.poller[SelectorPoller].map(_.get))
      .flatMap { poller =>
        Resource.make(IO(poller.provider.openPipe())) { pipe =>
          IO(pipe.sink().close()).guarantee(IO(pipe.source().close()))
        }
      }
      .evalTap { pipe =>
        IO {
          pipe.sink().configureBlocking(false)
          pipe.source().configureBlocking(false)
        }
      }

  "SelectorPoller" should {

    "notify read-ready events" in real {
      mkPipe.use { pipe =>
        for {
          poller <- IO.poller[SelectorPoller].map(_.get)
          buf <- IO(ByteBuffer.allocate(4))
          _ <- IO(pipe.sink.write(ByteBuffer.wrap(Array(1, 2, 3)))).background.surround {
            poller.select(pipe.source, OP_READ) *> IO(pipe.source.read(buf))
          }
          _ <- IO(pipe.sink.write(ByteBuffer.wrap(Array(42)))).background.surround {
            poller.select(pipe.source, OP_READ) *> IO(pipe.source.read(buf))
          }
        } yield buf.array().toList must be_==(List[Byte](1, 2, 3, 42))
      }
    }

    "setup multiple callbacks" in real {
      mkPipe.use { pipe =>
        for {
          poller <- IO.poller[SelectorPoller].map(_.get)
          _ <- poller.select(pipe.source, OP_READ).parReplicateA_(10) <&
            IO(pipe.sink.write(ByteBuffer.wrap(Array(1, 2, 3))))
        } yield ok
      }
    }

  }

}
