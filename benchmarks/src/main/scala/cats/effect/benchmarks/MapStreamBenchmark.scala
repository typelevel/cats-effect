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
 * benchmarks/run-benchmark MapStreamBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 * Jmh / run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.MapStreamBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class MapStreamBenchmark {
  import MapStreamBenchmark.streamTest

  @Benchmark
  def one(): Long = streamTest(12000, 1)

  @Benchmark
  def batch30(): Long = streamTest(1000, 30)

  @Benchmark
  def batch120(): Long = streamTest(100, 120)
}

object MapStreamBenchmark {
  def streamTest(times: Int, batchSize: Int): Long = {
    var stream = range(0, times)
    var i = 0
    while (i < batchSize) {
      stream = mapStream(addOne)(stream)
      i += 1
    }
    sum(0)(stream).unsafeRunSync()
  }

  final case class Stream(value: Int, next: IO[Option[Stream]])
  val addOne = (x: Int) => x + 1

  def range(from: Int, until: Int): Option[Stream] =
    if (from < until)
      Some(Stream(from, IO(range(from + 1, until))))
    else
      None

  def mapStream(f: Int => Int)(box: Option[Stream]): Option[Stream] =
    box match {
      case Some(Stream(value, next)) =>
        Some(Stream(f(value), next.map(mapStream(f))))
      case None =>
        None
    }

  def sum(acc: Long)(box: Option[Stream]): IO[Long] =
    box match {
      case Some(Stream(value, next)) =>
        next.flatMap(sum(acc + value))
      case None =>
        IO.pure(acc)
    }
}
