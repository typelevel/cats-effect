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

package cats.effect.example

import cats.effect.unsafe.{IORuntime, IORuntimeConfig}
import cats.effect.{IO, IOApp}

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

/**
 * This example shows the simplest way of spawning new fibers (via `.start`)
 * and demonstrates their cooperative scheduling (yielding) within the thread pool.
 *
 * Example of output:
 *
 * [pool-1-thread-2] A
 * [pool-1-thread-2] A
 * [pool-1-thread-1] B
 * [pool-1-thread-2] A
 * A CEDES
 * [pool-1-thread-1] B
 * [pool-1-thread-2] C
 * [pool-1-thread-1] B
 * [pool-1-thread-2] C
 * [pool-1-thread-1] B
 * B DONE
 * [pool-1-thread-2] C
 * [pool-1-thread-1] D
 * [pool-1-thread-2] C
 * C DONE
 * [pool-1-thread-1] D
 * [pool-1-thread-2] A
 * A DONE
 * [pool-1-thread-1] D
 * [pool-1-thread-1] D
 * D DONE
 *
 * Explanation:
 *
 * - Four fibers ("A", "B", "C", "D") are spawned on the fixed thread pool with two threads.
 * - After spawning, each of the two available threads starts running one fiber, e.g. A and B.
 * - Program that's being run on each fiber consists of a recursive function that takes
 *   an ID, used to keep track of different fibers ("A", "B", "C" and "D"), and a counter that
 *   is being decreased until it reaches 1. The function also has one particular behaviour -
 *   if the received ID equals "A" and the counter equals 2, it performs IO.cede, telling the
 *   running fiber to cooperatively yield its thread to other waiting fibers.
 * - On the third call of the recursive function, fiber A cedes; at that point, its thread
 *   starts running another fiber (e.g. C), while fiber B remains on its original thread.
 * - After four steps, B is done. The thread that was running B now takes a fresh fiber (e.g. D)
 *   from the pool of available fibers.
 * - Soon enough C is done as well, and its thread starts executing the only remaining fiber,
 *   which is the fiber A from earlier.
 * - Fibers A and D are concurrently executed on their respective threads until completion.
 *
 * NOTE:
 * IO(Thread.sleep) and IO(println) are used deliberately here, because
 * IO.sleep and IO.print cede, and for the sake of example we want to cede
 * only when it is initiated manually via IO.cede.
 */
object FiberCooperativeYielding extends IOApp.Simple {

  def ec: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

  override implicit val runtime: IORuntime =
    IORuntime(
      ec,
      IORuntime.createDefaultBlockingExecutionContext()._1,
      IORuntime.createDefaultScheduler()._1,
      () => (),
      IORuntimeConfig.apply()
    )

  def countdown(id: String)(i: Int): IO[Unit] =
    for {
      _ <- IO(Thread.sleep(200)) // throttle a bit
      _ <- IO(println(s"[${Thread.currentThread.getName}] $id"))
      _ <- if (id == "A" && i == 2) IO(println("A CEDES")) >> IO.cede else IO.unit
      _ <- if (i == 1) IO(println(s"$id DONE"))
      else countdown(id)(i - 1)
    } yield ()

  override def run: IO[Unit] =
    for {
      fiber1 <- countdown("A")(4).start
      fiber2 <- countdown("B")(4).start
      fiber3 <- countdown("C")(4).start
      fiber4 <- countdown("D")(4).start
      _ <- fiber1.join
      _ <- fiber2.join
      _ <- fiber3.join
      _ <- fiber4.join
    } yield ()
}

