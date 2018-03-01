/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

package cats.effect.laws.util

import cats.effect.internals.NonFatal
import cats.effect.laws.util.TestContext.State
import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContext
import scala.util.Random

/**
 * A `scala.concurrent.ExecutionContext` implementation that can be
 * used for testing purposes.
 *
 * Usage:
 *
 * {{{
 *   implicit val ec = TestContext()
 *
 *   ec.execute(new Runnable { def run() = println("task1") })
 *
 *   ex.execute(new Runnable {
 *     def run() = {
 *       println("outer")
 *
 *       ec.execute(new Runnable {
 *         def run() = println("inner")
 *       })
 *     }
 *   })
 *
 *   // Nothing executes until `tick` gets called
 *   ec.tick()
 *
 *   // Testing the resulting state
 *   assert(ec.state.tasks.isEmpty)
 *   assert(ec.state.lastReportedFailure == None)
 * }}}
 */
final class TestContext private () extends ExecutionContext {
  private[this] var stateRef = State(Queue.empty, None)

  def execute(r: Runnable): Unit =
    synchronized {
      stateRef = stateRef.copy(tasks = stateRef.tasks.enqueue(r))
    }

  def reportFailure(cause: Throwable): Unit =
    synchronized {
      stateRef = stateRef.copy(lastReportedFailure = Some(cause))
    }

  /**
   * Returns the internal state of the `TestContext`, useful for testing
   * that certain execution conditions have been met.
   */
  def state: State =
    synchronized(stateRef)

  /**
   * Triggers execution by going through the queue of scheduled tasks and
   * executing them all, until no tasks remain in the queue to execute.
   *
   * Order of execution isn't guaranteed, the queued `Runnable`s are
   * being shuffled in order to simulate the needed non-determinism
   * that happens with multi-threading.
   */
  @tailrec def tick(): Unit = {
    val queue = synchronized {
      val ref = stateRef.tasks
      stateRef = stateRef.copy(tasks = Queue.empty)
      ref
    }

    if (queue.nonEmpty) {
      // Simulating non-deterministic execution
      val batch = Random.shuffle(queue)
      for (r <- batch) try r.run() catch {
        case NonFatal(ex) =>
          synchronized {
            stateRef = stateRef.copy(lastReportedFailure = Some(ex))
          }
      }

      tick() // Next cycle please
    }
  }
}

object TestContext {
  /** Builder for [[TestContext]] instances. */
  def apply(): TestContext =
    new TestContext

  /**
   * The internal state of [[TestContext]].
   */
  final case class State(
    tasks: Queue[Runnable],
    lastReportedFailure: Option[Throwable]
  )
}
