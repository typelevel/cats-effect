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

  @Param(Array("1000000"))
  var size: Int = _

  @Benchmark
  def enqueueDequeueOne(): Unit = {
    val program = Queue.bounded[IO, Unit](size) flatMap { q =>
      def loop(i: Int): IO[Unit] =
        if (i > 0)
          q.offer(()) *> q.take >> loop(i - 1)
        else
          IO.unit

      loop(size)
    }

    program.unsafeRunSync()
  }

  @Benchmark
  def enqueueDequeueMany(): Unit = {
    val program = Queue.bounded[IO, Unit](size) flatMap { q =>
      def loopIn(i: Int): IO[Unit] =
        if (i > 0)
          q.offer(()) >> loopIn(i - 1)
        else
          IO.unit

      def loopOut(i: Int): IO[Unit] =
        if (i > 0)
          q.take >> loopOut(i - 1)
        else
          IO.unit

      loopIn(size) *> loopOut(size)
    }

    program.unsafeRunSync()
  }
}
