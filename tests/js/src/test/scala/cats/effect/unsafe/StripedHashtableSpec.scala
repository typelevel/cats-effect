/*
 * Copyright 2020-2024 Typelevel
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

import cats.syntax.traverse._

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

class StripedHashtableSpec extends BaseSpec {

  override def executionTimeout: FiniteDuration = 2.minutes

  def hashtableRuntime(): IORuntime =
    IORuntime(
      IORuntime.defaultComputeExecutionContext,
      IORuntime.defaultComputeExecutionContext,
      IORuntime.defaultScheduler,
      () => (),
      IORuntimeConfig()
    )

  "StripedHashtable" should {
    "work correctly in the presence of many unsafeRuns" in real {
      val iterations = 10000

      object Boom extends RuntimeException("Boom!")

      def io(n: Int): IO[Unit] =
        (n % 3) match {
          case 0 => IO.unit
          case 1 => IO.canceled
          case 2 => IO.raiseError[Unit](Boom)
        }

      Resource.make(IO(hashtableRuntime()))(rt => IO(rt.shutdown())).use { rt =>
        IO(new CountDownLatch(iterations)).flatMap { counter =>
          (0 until iterations)
            .toList
            .traverse { n => IO(io(n).unsafeRunAsync { _ => counter.countDown() }(rt)) }
            .flatMap { _ => IO.fromFuture(IO.delay(counter.await())) }
            .flatMap { _ =>
              IO.blocking {
                rt.fiberErrorCbs.synchronized {
                  rt.fiberErrorCbs.tables.forall { table =>
                    // check that each component hashtable of the larger striped
                    // hashtable is empty and has shrunk to its initial capacity
                    table.isEmpty && table.unsafeCapacity() == table.unsafeInitialCapacity()
                  } mustEqual true
                }
              }
            }
        }
      }
    }
  }

  /**
   * This implementation only works on Scala.js as it relies on a single threaded execution
   * model.
   */
  private final class CountDownLatch(private var counter: Int) {
    private val promise: Promise[Unit] = Promise()

    def countDown(): Unit = {
      counter -= 1
      if (counter == 0) {
        promise.success(())
      }
    }

    def await(): Future[Unit] =
      promise.future
  }
}
