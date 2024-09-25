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
 * Jmh / run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.QueueBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MINUTES)
class QueueBenchmark {

  @Param(Array("32768")) // must be a power of 2
  var size: Int = _

  @Benchmark
  def boundedConcurrentEnqueueDequeueOne(): Unit =
    Queue.boundedForConcurrent[IO, Unit](size).flatMap(enqueueDequeueOne(_)).unsafeRunSync()

  @Benchmark
  def boundedConcurrentEnqueueDequeueMany(): Unit =
    Queue.boundedForConcurrent[IO, Unit](size).flatMap(enqueueDequeueMany(_)).unsafeRunSync()

  @Benchmark
  def boundedConcurrentEnqueueDequeueContended(): Unit =
    Queue
      .boundedForConcurrent[IO, Unit](size / 8)
      .flatMap(enqueueDequeueContended(_))
      .unsafeRunSync()

  @Benchmark
  def boundedConcurrentEnqueueDequeueContendedSingleConsumer(): Unit =
    Queue
      .boundedForConcurrent[IO, Unit](size / 8)
      .flatMap(enqueueDequeueContendedSingleConsumer(_))
      .unsafeRunSync()

  @Benchmark
  def boundedAsyncEnqueueDequeueOne(): Unit =
    Queue.boundedForAsync[IO, Unit](size).flatMap(enqueueDequeueOne(_)).unsafeRunSync()

  @Benchmark
  def boundedAsyncEnqueueDequeueMany(): Unit =
    Queue.boundedForAsync[IO, Unit](size).flatMap(enqueueDequeueMany(_)).unsafeRunSync()

  @Benchmark
  def boundedAsyncEnqueueDequeueContended(): Unit =
    Queue
      .boundedForAsync[IO, Unit](size / 8)
      .flatMap(enqueueDequeueContended(_))
      .unsafeRunSync()

  @Benchmark
  def boundedAsyncEnqueueDequeueContendedSingleConsumer(): Unit =
    Queue
      .boundedForAsync[IO, Unit](size / 8)
      .flatMap(enqueueDequeueContendedSingleConsumer(_))
      .unsafeRunSync()

  @Benchmark
  def unboundedConcurrentEnqueueDequeueOne(): Unit =
    Queue.unboundedForConcurrent[IO, Unit].flatMap(enqueueDequeueOne(_)).unsafeRunSync()

  @Benchmark
  def unboundedConcurrentEnqueueDequeueMany(): Unit =
    Queue.unboundedForConcurrent[IO, Unit].flatMap(enqueueDequeueMany(_)).unsafeRunSync()

  @Benchmark
  def unboundedConcurrentEnqueueDequeueContended(): Unit =
    Queue.unboundedForConcurrent[IO, Unit].flatMap(enqueueDequeueContended(_)).unsafeRunSync()

  @Benchmark
  def unboundedAsyncEnqueueDequeueOne(): Unit =
    Queue.unboundedForAsync[IO, Unit].flatMap(enqueueDequeueOne(_)).unsafeRunSync()

  @Benchmark
  def unboundedAsyncEnqueueDequeueMany(): Unit =
    Queue.unboundedForAsync[IO, Unit].flatMap(enqueueDequeueMany(_)).unsafeRunSync()

  @Benchmark
  def unboundedAsyncEnqueueDequeueContended(): Unit =
    Queue.unboundedForAsync[IO, Unit].flatMap(enqueueDequeueContended(_)).unsafeRunSync()

  @Benchmark
  def droppingConcurrentEnqueueDequeueOne(): Unit =
    Queue.droppingForConcurrent[IO, Unit](size).flatMap(enqueueDequeueOne(_)).unsafeRunSync()

  @Benchmark
  def droppingConcurrentEnqueueDequeueMany(): Unit =
    Queue.droppingForConcurrent[IO, Unit](size).flatMap(enqueueDequeueMany(_)).unsafeRunSync()

  @Benchmark
  def droppingAsyncEnqueueDequeueOne(): Unit =
    Queue.droppingForAsync[IO, Unit](size).flatMap(enqueueDequeueOne(_)).unsafeRunSync()

  @Benchmark
  def droppingAsyncEnqueueDequeueMany(): Unit =
    Queue.droppingForAsync[IO, Unit](size).flatMap(enqueueDequeueMany(_)).unsafeRunSync()

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

  private[this] def enqueueDequeueContended(q: Queue[IO, Unit]): IO[Unit] = {
    def par(action: IO[Unit], num: Int): IO[Unit] =
      if (num <= 10)
        action.replicateA_(num)
      else
        par(action, num / 2) &> par(action, num / 2)

    val offerers = par(q.offer(()), size / 4)
    val takers = par(q.take, size / 4)

    offerers &> takers
  }

  private[this] def enqueueDequeueContendedSingleConsumer(q: Queue[IO, Unit]): IO[Unit] = {
    def par(action: IO[Unit], num: Int): IO[Unit] =
      if (num <= 10)
        action.replicateA_(num)
      else
        par(action, num / 2) &> par(action, num / 2)

    par(q.offer(()), size / 4) &> q.take.replicateA_(size / 4)
  }
}
