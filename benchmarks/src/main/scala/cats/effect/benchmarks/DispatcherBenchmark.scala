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
import cats.effect.std.Dispatcher
import cats.effect.unsafe._
import cats.syntax.all._

import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

/**
 * To do comparative benchmarks between versions:
 *
 * benchmarks/run-benchmark DispatcherBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 * Jmh / run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.DispatcherBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MINUTES)
class DispatcherBenchmark {

  @Param(Array("1000"))
  var size: Int = _

  def benchmark(implicit runtime: IORuntime): Int = {
    def fiber(dispatcher: Dispatcher[IO], i: Int): IO[Int] =
      IO.fromFuture(IO(dispatcher.unsafeToFuture {
        IO(i).flatMap { i =>
          IO.fromFuture(IO(dispatcher.unsafeToFuture {
            if (i > 100) {
              IO.fromFuture(IO(dispatcher.unsafeToFuture {
                IO.pure(i)
              }))
            } else {
              IO.fromFuture(IO(dispatcher.unsafeToFuture {
                fiber(dispatcher, i + 1)
              }))
            }
          }))
        }
      }))

    Dispatcher
      .parallel[IO](await = false)
      .use { disp =>
        List
          .range(0, size)
          .traverse(_ => fiber(disp, 0).start)
          .flatMap(_.traverse(_.joinWithNever))
          .map(_.sum)
      }
      .unsafeRunSync()
  }

  @Benchmark
  def scheduling(): Int = {
    import cats.effect.unsafe.implicits.global
    benchmark
  }
}
