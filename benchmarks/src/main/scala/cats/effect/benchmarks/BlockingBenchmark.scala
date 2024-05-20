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

package cats.effect
package benchmarks

import cats.effect.unsafe.implicits.global

import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

/**
 * To run the benchmark from within sbt:
 *
 * benchmarks/Jmh/run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.BlockingBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class BlockingBenchmark {

  @Param(Array("10000"))
  var size: Int = _

  /*
   * Uses `IO.blocking` around a very tiny region. As things stand, each time
   * `IO.blocking` is executed by the runtime, the whole computation is shifted
   * to a thread on the blocking EC and immediately shifted back to the compute
   * EC when the blocking ends.
   */
  @Benchmark
  def fine(): Int = {
    def loop(n: Int): IO[Int] =
      IO.blocking(42).flatMap { a =>
        if (n < size) loop(n + 1)
        else IO.pure(a)
      }

    loop(0).unsafeRunSync()
  }

  /*
   * Uses `IO.blocking` around a very big region. This should incur only a single
   * shift to the blocking EC and a single shift back to the compute EC.
   */
  @Benchmark
  def coarse(): Int = {
    def loop(n: Int): IO[Int] =
      IO(42).flatMap { a =>
        if (n < size) loop(n + 1)
        else IO.pure(a)
      }

    IO.blocking(loop(0).unsafeRunSync()).unsafeRunSync()
  }

  /*
   * Uses `IO.blocking` around a very big region, but the code inside the blocking
   * region also contains smaller blocking regions.
   */
  @Benchmark
  def nested(): Int = {
    def loop(n: Int): IO[Int] =
      IO.blocking(42).flatMap { a =>
        if (n < size) loop(n + 1)
        else IO.pure(a)
      }

    IO.blocking(loop(0).unsafeRunSync()).unsafeRunSync()
  }

  /*
   * Cedes after every blocking operation.
   */
  @Benchmark
  def blockThenCede(): Int = {
    def loop(n: Int): IO[Int] =
      IO.blocking(42).flatMap { a =>
        if (n < size) IO.cede.flatMap(_ => loop(n + 1))
        else IO.cede.flatMap(_ => IO.pure(a))
      }

    loop(0).unsafeRunSync()
  }
}
