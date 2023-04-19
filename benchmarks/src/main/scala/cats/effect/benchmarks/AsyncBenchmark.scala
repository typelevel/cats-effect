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
 * benchmarks/run-benchmark AsyncBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 * Jmh / run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.AsyncBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class AsyncBenchmark {

  @Param(Array("100"))
  var size: Int = _

  def evalAsync(n: Int): IO[Int] =
    IO.async_(_(Right(n)))

  def evalCancelable(n: Int): IO[Int] =
    IO.async[Int] { cb =>
      IO {
        cb(Right(n))
        Some(IO.unit)
      }
    }

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

  // TODO
  /*@Benchmark
  def parMap2() = {
    val task = (0 until size).foldLeft(IO(0))((acc, i) => (acc, IO(i)).parMapN(_ + _))
    task.unsafeRunSync()
  }*/

  @Benchmark
  def race() = {
    val task = (0 until size).foldLeft(IO.never[Int])((acc, _) =>
      IO.race(acc, IO(1)).map {
        case Left(i) => i
        case Right(i) => i
      })

    task.unsafeRunSync()
  }

  @Benchmark
  def racePair() = {
    val task = (0 until size).foldLeft(IO.never[Int])((acc, _) =>
      IO.racePair(acc, IO(1)).flatMap {
        case Left((oc, fiber)) =>
          fiber.cancel.flatMap(_ => oc.embedNever)
        case Right((fiber, oc)) =>
          fiber.cancel.flatMap(_ => oc.embedNever)
      })

    task.unsafeRunSync()
  }

  @Benchmark
  def start() = {
    def loop(i: Int): IO[Int] =
      if (i < size) IO(i + 1).start.flatMap(_.joinWithNever).flatMap(loop)
      else
        IO.pure(i)

    IO(0).flatMap(loop).unsafeRunSync()
  }

  @Benchmark
  def uncancelable() = {
    def loop(i: Int): IO[Int] =
      if (i < size)
        IO(i + 1).uncancelable.flatMap(loop)
      else
        IO.pure(i)

    IO(0).flatMap(loop).unsafeRunSync()
  }

  @Benchmark
  def bracket() = {
    def loop(i: Int): IO[Int] =
      if (i < size)
        IO(i).bracket(i => IO(i + 1))(_ => IO.unit).flatMap(loop)
      else
        IO.pure(i)

    IO(0).flatMap(loop).unsafeRunSync()
  }
}
