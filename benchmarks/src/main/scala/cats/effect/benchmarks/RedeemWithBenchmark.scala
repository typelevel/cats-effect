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

import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

/**
 * To do comparative benchmarks between versions:
 *
 * benchmarks/run-benchmark RedeemWithBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 * Jmh / run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.RedeemWithBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class RedeemWithBenchmark {

  @Param(Array("10000"))
  var size: Int = _

  @Benchmark
  def happyPath(): Int = {
    val recover: Throwable => IO[Int] = _ => IO.pure(0)

    def loop(i: Int): IO[Int] =
      if (i < size) IO.pure(i + 1).redeemWith(recover, loop)
      else IO.pure(i)

    loop(0).unsafeRunSync()
  }

  @Benchmark
  def errorRaised(): Int = {
    val dummy = new RuntimeException("dummy")
    val ioIncrement: Int => IO[Int] = x => IO.pure(x + 1)
    val id = IO.pure[Int] _

    def loop(i: Int): IO[Int] =
      if (i < size)
        IO.raiseError[Int](dummy).flatMap(ioIncrement).redeemWith(_ => loop(i + 1), id)
      else
        IO.pure(i)

    loop(0).unsafeRunSync()
  }
}
