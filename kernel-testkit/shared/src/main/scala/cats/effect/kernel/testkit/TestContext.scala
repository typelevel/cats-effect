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

package cats.effect.kernel
package testkit

import scala.annotation.tailrec
import scala.collection.immutable.SortedSet
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Random, Try}
import scala.util.control.NonFatal

import java.util.{Base64, Comparator}
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

/**
 * A [[scala.concurrent.ExecutionContext]] implementation that can simulate async boundaries and
 * time passage, useful for law testing purposes. This is intended primarily for datatype
 * implementors. Most end-users will be better served by the `cats.effect.testkit.TestControl`
 * utility, rather than using `TestContext` directly.
 *
 * Usage for simulating an `ExecutionContext`):
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
final class TestContext private (_seed: Long) extends ExecutionContext { self =>
  import TestContext.{ConcurrentState, Encoder, State, Task}

  private[this] val random = new Random(_seed)

  private[this] val stateRef = new ConcurrentState()

  def execute(runnable: Runnable): Unit = {
    val current = stateRef
    val newID = current.currentID.incrementAndGet()
    val clock = current.currentNanos.get().nanos
    val rnd = random.nextLong()
    val task = Task(newID, runnable, clock, rnd)
    current.tasks.add(task)
    ()
  }

  def reportFailure(cause: Throwable): Unit = {
    stateRef.lastReportedFailure.set(cause)
  }

  /**
   * Returns the internal state of the `TestContext`, useful for testing that certain execution
   * conditions have been met.
   */
  def state: State = {
    val current = stateRef

    val builder = SortedSet.newBuilder[Task]
    val iterator = current.tasks.iterator()
    while (iterator.hasNext()) {
      builder += iterator.next()
    }

    State(
      lastID = current.currentID.get(),
      clock = current.currentNanos.get().nanos,
      tasks = builder.result(),
      lastReportedFailure = Option(current.lastReportedFailure.get())
    )
  }

  /**
   * Returns the current interval between "now" and the earliest scheduled task. If there are
   * tasks which will run immediately, this will return `Duration.Zero`. Passing this value to
   * [[tick]] will guarantee minimum time-oriented progress on the task queue (e.g.
   * `tick(nextInterval())`).
   */
  def nextInterval(): FiniteDuration = {
    val current = stateRef
    if (current.tasks.isEmpty) {
      Duration.Zero
    } else {
      val diff = current.tasks.first().runsAt - current.currentNanos.get().nanos
      diff.max(Duration.Zero)
    }
  }

  def advance(time: FiniteDuration): Unit = {
    require(time > Duration.Zero)
    stateRef.currentNanos.addAndGet(time.toNanos)
    ()
  }

  def advanceAndTick(time: FiniteDuration): Unit = {
    advance(time)
    tick()
  }

  /**
   * Executes just one tick, one task, from the internal queue, useful for testing that a some
   * runnable will definitely be executed next.
   *
   * Returns a boolean indicating that tasks were available and that the head of the queue has
   * been executed, so normally you have this equivalence:
   *
   * {{{
   *   while (ec.tickOne()) {}
   *   // ... is equivalent with:
   *   ec.tick()
   * }}}
   *
   * Note that ask extraction has a random factor, the behavior being like [[tick]], in order to
   * simulate nondeterminism. So you can't rely on some ordering of execution if multiple tasks
   * are waiting execution.
   *
   * @return
   *   `true` if a task was available in the internal queue, and was executed, or `false`
   *   otherwise
   */
  @tailrec
  def tickOne(): Boolean = {
    val current = stateRef

    Try(current.tasks.first()).toOption match {
      case Some(head) =>
        if (head.runsAt <= current.currentNanos.get().nanos) {
          if (current.tasks.remove(head)) {
            // execute task
            try head.task.run()
            catch { case ex if NonFatal(ex) => reportFailure(ex) }
            true
          } else {
            tickOne()
          }
        } else {
          false
        }

      case None => false
    }
  }

  @tailrec
  def tick(): Unit =
    if (tickOne()) {
      tick()
    }

  private[testkit] def tick(time: FiniteDuration): Unit = {
    tick()
    advanceAndTick(time)
  }

  private[testkit] def tick$default$1(): FiniteDuration = Duration.Zero

  /**
   * Repeatedly runs `tick(nextInterval())` until all work has completed. This is useful for
   * emulating the quantized passage of time. For any discrete tick, the scheduler will randomly
   * pick from all eligible tasks until the only remaining work is delayed. At that point, the
   * scheduler will then advance the minimum delay (to the next time interval) and the process
   * repeats.
   *
   * This is intuitively equivalent to "running to completion".
   */
  @tailrec
  def tickAll(): Unit = {
    tick()
    if (!stateRef.tasks.isEmpty) {
      advance(nextInterval())
      tickAll()
    }
  }

  private[testkit] def tickAll(time: FiniteDuration): Unit = {
    val _ = time
    tickAll()
  }

  private[testkit] def tickAll$default$1(): FiniteDuration = Duration.Zero

  def schedule(delay: FiniteDuration, runnable: Runnable): () => Unit = {
    val current = stateRef

    val d = if (delay >= Duration.Zero) delay else Duration.Zero
    val newID = current.currentID.incrementAndGet()
    val clock = current.currentNanos.get().nanos
    val rnd = random.nextLong()

    val task = Task(newID, runnable, clock + d, rnd)
    val cancelable = () => cancelTask(task)
    current.tasks.add(task)

    cancelable
  }

  def derive(): ExecutionContext =
    new ExecutionContext {
      def execute(runnable: Runnable): Unit = self.execute(runnable)
      def reportFailure(cause: Throwable): Unit = self.reportFailure(cause)
    }

  /**
   * Derives a new `ExecutionContext` which delegates to `this`, but wrapping all tasks in
   * [[scala.concurrent.blocking]].
   */
  def deriveBlocking(): ExecutionContext =
    new ExecutionContext {
      import scala.concurrent.blocking

      def execute(runnable: Runnable): Unit = blocking(self.execute(runnable))
      def reportFailure(cause: Throwable): Unit = self.reportFailure(cause)
    }

  def now(): FiniteDuration = stateRef.currentNanos.get().nanos

  def seed: String =
    new String(Encoder.encode(_seed.toString.getBytes))

  private def cancelTask(t: Task): Unit = {
    stateRef.tasks.remove(t)
    ()
  }
}

object TestContext {

  private val Decoder = Base64.getDecoder()
  private val Encoder = Base64.getEncoder()

  /**
   * Builder for [[TestContext]] instances. Utilizes a random seed, which may be obtained from
   * the [[TestContext#seed]] method.
   */
  def apply(): TestContext =
    new TestContext(Random.nextLong())

  /**
   * Constructs a new [[TestContext]] using the given seed, which must be encoded as base64.
   * Assuming this seed was produced by another `TestContext`, running the same program against
   * the new context will result in the exact same task interleaving as happened in the previous
   * context, provided that the same tasks are interleaved. Note that subtle differences between
   * different runs of identical programs are possible, particularly if one program auto-`cede`s
   * in a different place than the other one. This is an excellent and reliable mechanism for
   * small, tightly-controlled programs with entirely deterministic side-effects, and a
   * completely useless mechanism for anything where the scheduler ticks see different task
   * lists despite identical configuration.
   */
  def apply(seed: String): TestContext =
    new TestContext(new String(Decoder.decode(seed)).toLong)

  final class ConcurrentState(
      val currentID: AtomicLong = new AtomicLong(),
      val currentNanos: AtomicLong = new AtomicLong(),
      val tasks: ConcurrentSkipListSet[Task] = new ConcurrentSkipListSet(Task.comparator),
      val lastReportedFailure: AtomicReference[Throwable] = new AtomicReference()
  )

  /**
   * Used internally by [[TestContext]], represents the internal state used for task scheduling
   * and execution.
   */
  final case class State(
      lastID: Long,
      clock: FiniteDuration,
      tasks: SortedSet[Task],
      lastReportedFailure: Option[Throwable])

  /**
   * Used internally by [[TestContext]], represents a unit of work pending execution.
   */
  final case class Task(id: Long, task: Runnable, runsAt: FiniteDuration, private val rnd: Long)

  /**
   * Internal API — defines ordering for [[Task]], to be used by `SortedSet`.
   */
  private[TestContext] object Task {
    implicit val ordering: Ordering[Task] =
      new Ordering[Task] {
        val longOrd = implicitly[Ordering[Long]]

        def compare(x: Task, y: Task): Int =
          x.runsAt.compare(y.runsAt) match {
            case 0 => longOrd.compare(x.id + x.rnd, y.id + y.rnd)
            case nonZero => nonZero
          }
      }

    val comparator: Comparator[Task] = ordering.compare
  }
}
