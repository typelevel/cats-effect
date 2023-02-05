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

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.{catsSyntaxParallelTraverse1, toTraverseOps}

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/**
 * To do comparative benchmarks between versions:
 *
 * benchmarks/run-benchmark ParallelBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 * Jmh / run -i 10 -wi 10 -f 2 -t 4 cats.effect.benchmarks.ParallelBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "4 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class ParallelBenchmark {

  @Param(Array( /*"100", */ "1000" /*, "10000"*/ ))
  var size: Int = _

  @Param(Array( /*"100", "1000", */ "10000" /*, "100000", "1000000"*/ ))
  var cpuTokens: Long = _

  @Benchmark
  def parTraverse(): Unit =
    1.to(size).toList.parTraverse(_ => IO(Blackhole.consumeCPU(cpuTokens))).void.unsafeRunSync()

  @Benchmark
  def traverse(): Unit =
    1.to(size).toList.traverse(_ => IO(Blackhole.consumeCPU(cpuTokens))).void.unsafeRunSync()
}
