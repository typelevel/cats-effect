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
package unsafe

import cats.effect.unsafe.UringSystem.liburingOps._

import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.errno._
import scala.scalanative.posix.fcntl._
import scala.scalanative.posix.string._
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe._

import java.io.IOException

class UringSystemSuite extends BaseSuite {

  override def munitIgnore: Boolean = !LinktimeInfo.isLinux

  private[this] var uringRuntime: IORuntime = _

  override def runtime(): IORuntime = uringRuntime

  override def beforeAll(): Unit = {
    val (blocking, blockDown) =
      IORuntime.createDefaultBlockingExecutionContext(
        threadPrefix = s"io-blocking-${getClass.getName}")
    val (compute, api, compDown) =
      IORuntime.createWorkStealingComputeThreadPool(
        threadPrefix = s"io-compute-${getClass.getName}",
        blockerThreadPrefix = s"io-blocker-${getClass.getName}",
        pollingSystem = UringSystem
      )
    uringRuntime = IORuntime(
      compute,
      blocking,
      compute,
      List(api),
      { () =>
        compDown()
        blockDown()
      },
      IORuntimeConfig()
    )
  }

  override def afterAll(): Unit =
    if (uringRuntime ne null) uringRuntime.shutdown()

  real("start the UringSystem") {
    UringSystem.Uring.get.map(uring => assert(uring ne null))
  }

  real("submit a nop SQE and resume on completion") {
    UringSystem
      .Uring
      .get
      .flatMap { uring => uring.call(io_uring_prep_nop) }
      .map(rtn => assertEquals(rtn, 0))
  }

  real("submit a nop SQE and resume on completion many times in a row") {
    UringSystem.Uring.get.flatMap { uring =>
      val op = uring.call(io_uring_prep_nop).map(rtn => assertEquals(rtn, 0))
      op.replicateA_(20)
    }
  }

  real("submit nop SQEs in parallel and resume on completion") {
    UringSystem.Uring.get.flatMap { uring =>
      val op = uring.call(io_uring_prep_nop).map(rtn => assertEquals(rtn, 0))
      op.parReplicateA_(10)
    }
  }

  real("submit nop SQEs in parallel and resume on completion many times in a row") {
    UringSystem.Uring.get.flatMap { uring =>
      val op = uring.call(io_uring_prep_nop).map(rtn => assertEquals(rtn, 0))
      op.replicateA_(20).parReplicateA_(10)
    }
  }

  real("cancel a pending poll_add") {
    pipeHandle.use {
      case (readFd, _) =>
        Resource
          .eval(FileDescriptorPoller.get)
          .flatMap(_.registerFileDescriptor(readFd, true, false))
          .use { handle =>
            Deferred[IO, Unit].flatMap { entered =>
              val read =
                handle.pollReadRec(()) { _ => entered.complete(()).void *> IO(Left(())) }
              read.start.flatMap { fiber =>
                entered.get *> fiber.cancel *> fiber.join.map(oc => assert(oc.isCanceled))
              }
            }
          }
    }
  }

  /////////////////////////////////////////////////////////////////
  // Helpers
  /////////////////////////////////////////////////////////////////

  private def pipeHandle: Resource[IO, (Int, Int)] =
    Resource
      .make {
        IO {
          val fd = stackalloc[CInt](2)
          if (unistd.pipe(fd) != 0)
            throw new IOException(fromCString(strerror(errno)))
          (fd(0), fd(1))
        }
      } {
        case (readFd, writeFd) =>
          IO {
            unistd.close(readFd)
            unistd.close(writeFd)
            ()
          }
      }
      .evalTap {
        case (readFd, writeFd) =>
          IO {
            if (fcntl(readFd, F_SETFL, O_NONBLOCK) != 0)
              throw new IOException(fromCString(strerror(errno)))
            if (fcntl(writeFd, F_SETFL, O_NONBLOCK) != 0)
              throw new IOException(fromCString(strerror(errno)))
          }
      }
}
