/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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
import cats.implicits._
import org.openjdk.jmh.annotations._
import scala.concurrent.ExecutionContext.Implicits.global

/** To do comparative benchmarks between versions:
 *
 *     benchmarks/run-benchmark AsyncBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within SBT:
 *
 *     jmh:run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.AsyncBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread".
 * Please note that benchmarks should be usually executed at least in
 * 10 iterations (as a rule of thumb), but more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class AsyncBenchmark {
  @Param(Array("10000"))
  var size: Int = _

  def evalAsync(n: Int): IO[Int] =
    IO.async(_(Right(n)))

  def evalCancelable(n: Int): IO[Int] =
    IO.cancelable[Int] { cb => cb(Right(n)); IO.unit }

  @Benchmark
  def async() = {
    def loop(i: Int): IO[Int] =
      if (i < size) evalAsync(i + 1).flatMap(loop)
      else evalAsync(i)

    IO(0).flatMap(loop).unsafeRunSync()
  }

  @Benchmark
  def cancelable() = {
    def loop(i: Int): IO[Int] =
      if (i < size) evalCancelable(i + 1).flatMap(loop)
      else evalCancelable(i)

    IO(0).flatMap(loop).unsafeRunSync()
  }

  @Benchmark
  def parMap2() = {
    def loop(i: Int): IO[Int] =
      if (i < size) (IO(i + 1), IO(i)).parMapN((i, _) => i).flatMap(loop)
      else IO.pure(i)

    IO(0).flatMap(loop).unsafeRunSync()
  }

  @Benchmark
  def race() = {
    def loop(i: Int): IO[Int] =
      if (i < size) IO.race(IO(i + 1), IO(i + 1)).flatMap {
        case Left(i) => loop(i)
        case Right(i) => loop(i)
      }
      else {
        IO.pure(i)
      }

    IO(0).flatMap(loop).unsafeRunSync()
  }
}
