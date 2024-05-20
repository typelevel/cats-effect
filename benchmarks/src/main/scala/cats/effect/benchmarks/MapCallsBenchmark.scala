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
import cats.effect.unsafe.implicits.global

import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

/**
 * To do comparative benchmarks between versions:
 *
 * benchmarks/run-benchmark MapCallsBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 * Jmh / run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.MapCallsBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class MapCallsBenchmark {
  import MapCallsBenchmark.test

  @Benchmark
  def one(): Long = test(12000, 1)

  @Benchmark
  def batch30(): Long = test(12000 / 30, 30)

  @Benchmark
  def batch120(): Long = test(12000 / 120, 120)
}

object MapCallsBenchmark {
  def test(iterations: Int, batch: Int): Long = {
    val f = (x: Int) => x + 1
    var io = IO(0)

    var j = 0
    while (j < batch) { io = io.map(f); j += 1 }

    var sum = 0L
    var i = 0
    while (i < iterations) {
      sum += io.unsafeRunSync()
      i += 1
    }
    sum
  }
}
