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

package cats.effect.benchmarks

import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global

import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

/**
 * To do comparative benchmarks between versions:
 *
 * benchmarks/run-benchmark QueueBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 * jmh:run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.QueueBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MINUTES)
class QueueBenchmark {

  @Param(Array("100000"))
  var size: Int = _

  @Benchmark
  def concurrentEnqueueDequeueOne(): Unit =
    Queue.boundedForConcurrent[IO, Unit](size).flatMap(enqueueDequeueOne(_)).unsafeRunSync()

  @Benchmark
  def concurrentEnqueueDequeueMany(): Unit =
    Queue.boundedForConcurrent[IO, Unit](size).flatMap(enqueueDequeueMany(_)).unsafeRunSync()

  @Benchmark
  def concurrentDequeueEnqueueMany(): Unit =
    Queue.boundedForConcurrent[IO, Unit](size).flatMap(dequeueEnqueueMany(_)).unsafeRunSync()

  @Benchmark
  def concurrentEnqueueDequeueContended(): Unit =
    Queue.boundedForConcurrent[IO, Unit](size / 8).flatMap(enqueueDequeueContended(_)).unsafeRunSync()

  @Benchmark
  def asyncEnqueueDequeueOne(): Unit =
    Queue.boundedForAsync[IO, Unit](size).flatMap(enqueueDequeueOne(_)).unsafeRunSync()

  @Benchmark
  def asyncEnqueueDequeueMany(): Unit =
    Queue.boundedForAsync[IO, Unit](size).flatMap(enqueueDequeueMany(_)).unsafeRunSync()

  @Benchmark
  def asyncDequeueEnqueueMany(): Unit =
    Queue.boundedForAsync[IO, Unit](size).flatMap(dequeueEnqueueMany(_)).unsafeRunSync()

  @Benchmark
  def asyncEnqueueDequeueContended(): Unit =
    Queue.boundedForAsync[IO, Unit](size / 8).flatMap(enqueueDequeueContended(_)).unsafeRunSync()

  private[this] def enqueueDequeueOne(q: Queue[IO, Unit]): IO[Unit] = {
    def loop(i: Int): IO[Unit] =
      if (i > 0)
        q.offer(()) *> q.take >> loop(i - 1)
      else
        IO.unit

    loop(size)
  }

  private[this] def loopIn(q: Queue[IO, Unit], i: Int): IO[Unit] =
    if (i > 0)
      q.offer(()) >> loopIn(q, i - 1)
    else
      IO.unit

  private[this] def loopOut(q: Queue[IO, Unit], i: Int): IO[Unit] =
    if (i > 0)
      q.take >> loopOut(q, i - 1)
    else
      IO.unit

  private[this] def enqueueDequeueMany(q: Queue[IO, Unit]): IO[Unit] =
    loopIn(q, size) *> loopOut(q, size)

  private[this] def dequeueEnqueueMany(q: Queue[IO, Unit]): IO[Unit] =
    loopOut(q, size) *> loopIn(q, size)

  private[this] def enqueueDequeueContended(q: Queue[IO, Unit]): IO[Unit] = {
    def par(action: IO[Unit], num: Int): IO[Unit] =
      if (num <= 10)
        action
      else
        par(action, num / 2) &> par(action, num / 2)

    val offerers = par(q.offer(()), size / 4)
    val takers = par(q.take, size / 4)

    offerers &> takers
  }
}
