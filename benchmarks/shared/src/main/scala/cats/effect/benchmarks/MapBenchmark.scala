/*
 * Copyright 2017 Typelevel
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

import java.util.concurrent.TimeUnit
import cats.effect.IO
import org.openjdk.jmh.annotations._

/** To do comparative benchmarks between versions:
  *
  *     benchmarks/run-benchmark MapBenchmark
  *
  * This will generate results in `benchmarks/results`.
  *
  * Or to run the benchmark from within SBT:
  *
  *     jmh:run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.MapBenchmark
  *
  * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread".
  * Please note that benchmarks should be usually executed at least in
  * 10 iterations (as a rule of thumb), but more is better.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class MapBenchmark {
  @Benchmark
  def one(): Int = {
    def loop(io: IO[Int], count: Int): IO[Int] =
      if (count > 0)
        io.flatMap(_ => io.map(_ + 1))
      else
        io
    
    loop(IO(0), 1000).unsafeRunSync()
  }

  @Benchmark
  def batch(): Int = {
    def loop(io: IO[Int], count: Int): IO[Int] =
      if (count <= 0) io else io.flatMap { _ =>
        var io2 = io
        var i = 0
        while (i < 30) {
          io2 = io2.map(_ + 1)
          i += 1
        }
        io2
      }

    loop(IO(0), 1000).unsafeRunSync()
  }

  @Benchmark
  def many(): Int = {
    def loop(io: IO[Int], count: Int): IO[Int] =
      if (count <= 0) io else io.flatMap { _ =>
        var io2 = io
        var i = 0
        while (i < 1000) {
          io2 = io2.map(_ + 1)
          i += 1
        }
        io2
      }

    loop(IO(0), 1000).unsafeRunSync()
  }
}
