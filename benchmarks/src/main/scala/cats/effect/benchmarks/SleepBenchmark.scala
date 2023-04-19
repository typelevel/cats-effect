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
import cats.effect.unsafe._
import cats.syntax.all._

import org.openjdk.jmh.annotations._

import scala.concurrent.duration._

import java.util.concurrent.TimeUnit

/**
 * To do comparative benchmarks between versions:
 *
 * benchmarks/run-benchmark SleepBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 * jmh:run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.SleepBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MINUTES)
class SleepBenchmark {

  @Param(Array("10000"))
  var size: Int = _

  def sleepBenchmark(implicit runtime: IORuntime): Int = {
    def fiber(i: Int): IO[Int] =
      IO.sleep(1.nanosecond).flatMap { _ =>
        IO(i).flatMap { j =>
          IO.sleep(1.nanosecond).flatMap { _ =>
            if (j > 1000)
              IO.sleep(1.nanosecond).flatMap(_ => IO.pure(j))
            else
              IO.sleep(1.nanosecond).flatMap(_ => fiber(j + 1))
          }
        }
      }

    List
      .range(0, size)
      .traverse(fiber(_).start)
      .flatMap(_.traverse(_.joinWithNever))
      .map(_.sum)
      .unsafeRunSync()
  }

  def sleepRaceBenchmark(implicit runtime: IORuntime): Int = {
    def fiber(i: Int): IO[Int] = {
      val sleepRace = IO.race(IO.sleep(1.nanosecond), IO.sleep(1000.nanoseconds))
      sleepRace.flatMap { _ =>
        IO(i).flatMap { j =>
          sleepRace.flatMap { _ =>
            if (j > 1000)
              sleepRace.flatMap(_ => IO.pure(j))
            else
              sleepRace.flatMap(_ => fiber(j + 1))
          }
        }
      }
    }

    List
      .range(0, size)
      .traverse(fiber(_).start)
      .flatMap(_.traverse(_.joinWithNever))
      .map(_.sum)
      .unsafeRunSync()
  }

  @Benchmark
  def sleep(): Int = {
    import cats.effect.unsafe.implicits.global
    sleepBenchmark
  }

  @Benchmark
  def sleepRace(): Int = {
    import cats.effect.unsafe.implicits.global
    sleepRaceBenchmark
  }
}
