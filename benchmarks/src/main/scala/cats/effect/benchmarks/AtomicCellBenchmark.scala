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
import cats.effect.std._
import cats.effect.unsafe.implicits.global

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
  @Param(Array("10", "50", "100"))
  var fibers: Int = _

  @Param(Array("1000"))
  var iterations: Int = _

  private def happyPathImpl(cell: IO[AtomicCell[IO, Int]]): Unit = {
    cell
      .flatMap { c => c.evalUpdate(i => IO(i + 1)).replicateA_(fibers) }
      .replicateA_(iterations)
      .unsafeRunSync()
  }

  @Benchmark
  def happyPathConcurrent(): Unit = {
    happyPathImpl(cell = AtomicCell.concurrent(0))
  }

  @Benchmark
  def happyPathAsync(): Unit = {
    happyPathImpl(cell = AtomicCell.async(0))
  }

  private def highContentionImpl(cell: IO[AtomicCell[IO, Int]]): Unit = {
    cell
      .flatMap { c => c.evalUpdate(i => IO(i + 1)).parReplicateA_(fibers) }
      .replicateA_(iterations)
      .unsafeRunSync()
  }

  @Benchmark
  def highContentionConcurrent(): Unit = {
    highContentionImpl(cell = AtomicCell.concurrent(0))
  }

  @Benchmark
  def highContentionAsync(): Unit = {
    highContentionImpl(cell = AtomicCell.async(0))
  }

  private def cancellationImpl(cell: IO[AtomicCell[IO, Int]]): Unit = {
    cell
      .flatMap { c =>
        c.evalUpdate { _ =>
          c.evalUpdate(i => IO(i + 1))
            .start
            .flatMap(fiber => IO.cede >> fiber.cancel)
            .parReplicateA_(fibers)
            .as(-1)
        }
      }
      .replicateA_(iterations)
      .unsafeRunSync()
  }

  @Benchmark
  def cancellationConcurrent(): Unit = {
    cancellationImpl(cell = AtomicCell.concurrent(0))
  }

  @Benchmark
  def cancellationAsync(): Unit = {
    cancellationImpl(cell = AtomicCell.async(0))
  }
}
