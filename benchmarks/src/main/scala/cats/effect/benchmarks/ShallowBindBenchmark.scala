/*
 * Copyright 2020 Typelevel
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
 *     benchmarks/run-benchmark ShallowBindBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 *     jmh:run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.ShallowBindBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread".
 * Please note that benchmarks should be usually executed at least in
 * 10 iterations (as a rule of thumb), but more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class ShallowBindBenchmark {

  @Param(Array("10000"))
  var size: Int = _

  @Benchmark
  def pure(): Int = {
    def loop(i: Int): IO[Int] =
      if (i < size) IO.pure(i + 1).flatMap(loop)
      else IO.pure(i)

    IO.pure(0).flatMap(loop).unsafeRunSyncBenchmark()
  }

  @Benchmark
  def delay(): Int = {
    def loop(i: Int): IO[Int] =
      if (i < size) IO(i + 1).flatMap(loop)
      else IO(i)

    IO(0).flatMap(loop).unsafeRunSyncBenchmark()
  }

  @Benchmark
  def async(): Int = {
    def loop(i: Int): IO[Int] =
      if (i < size) IO.cede.flatMap(_ => IO.pure(i + 1)).flatMap(loop)
      else IO.cede.flatMap(_ => IO.pure(i))

    IO(0).flatMap(loop).unsafeRunSync()
  }
}
