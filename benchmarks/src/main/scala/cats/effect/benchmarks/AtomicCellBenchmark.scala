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
import cats.effect.std._
import cats.effect.unsafe.implicits.global
import cats.syntax.all._

import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

/**
 * To do comparative benchmarks between versions:
 *
 * benchmarks/run-benchmark AtomicCellBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 * Jmh / run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.AtomicCellBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class AtomicCellBenchmark {
  @Param(Array("1000"))
  var size: Int = _

  @Param(Array("10", "100", "1000"))
  var count: Int = _

  @Param(Array("10000"))
  var iterations: Int = _

  def initialSpots(): Vector[Boolean] = {
    Vector.tabulate(size) { i => (i % 2) == 0 }
  }

  private def evalModifyImpl(cell: IO[AtomicCell[IO, Vector[Boolean]]]): Unit = {
    (cell, Random.scalaUtilRandomSeedLong[IO](seed = 135L))
      .flatMapN {
        case (data, rnd) =>
          data
            .evalModify { spots =>
              val availableSpots = spots.zipWithIndex.collect { case (true, idx) => idx }
              rnd.shuffleVector(availableSpots).map { shuffled =>
                val acquired = shuffled.headOption
                val next = acquired.fold(spots)(a => spots.updated(a, false))
                (next, shuffled.headOption)
              }
            }
            .replicateA_(count)
      }
      .replicateA_(iterations)
      .unsafeRunSync()
  }

  @Benchmark
  def evalModifyConcurrent(): Unit = {
    evalModifyImpl(cell = AtomicCell.concurrent(initialSpots()))
  }

  @Benchmark
  def evalModifyAsync(): Unit = {
    evalModifyImpl(cell = AtomicCell.async(initialSpots()))
  }
}
