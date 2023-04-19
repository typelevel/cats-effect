/*
 * Copyright 2020-2023 Typelevel
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

import cats.effect._
import cats.effect.unsafe.implicits.global
import cats.syntax.all._

import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

/**
 * To do comparative benchmarks between versions:
 *
 * benchmarks/run-benchmark DeferredBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 * Jmh / run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.DeferredBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class DeferredBenchmark {

  @Param(Array("10", "100", "1000"))
  var count: Int = _

  @Benchmark
  def getBefore(): Unit = {
    IO.deferred[Unit]
      .flatMap(d => d.complete(()) *> d.get.replicateA_(count))
      .replicateA_(1000)
      .unsafeRunSync()
  }

  @Benchmark
  def getAfter(): Unit = {
    IO.deferred[Unit]
      .flatMap(d => d.complete(()) >> d.get.replicateA_(count))
      .replicateA_(1000)
      .unsafeRunSync()
  }

  @Benchmark
  def complete(): Unit = {
    IO.deferred[Unit]
      .flatMap { d => d.get.parReplicateA_(count) &> d.complete(()) }
      .replicateA_(1000)
      .unsafeRunSync()
  }

  @Benchmark
  def cancel(): Unit = {
    IO.deferred[Unit]
      .flatMap { d => d.get.start.replicateA(count).flatMap(_.traverse(_.cancel)) }
      .replicateA_(1000)
      .unsafeRunSync()
  }
}
