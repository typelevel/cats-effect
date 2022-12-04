/*
 * Copyright 2020-2022 Typelevel
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

import cats.effect._
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global

import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

/**
 * To do comparative benchmarks between versions:
 *
 * benchmarks/run-benchmark RefBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 * jmh:run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.RefBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class RefBenchmark {

  @Benchmark
  def modifySpecialized(): Unit = RefBenchmark.modifySpecialized(10000)

  @Benchmark
  def getAndUpdateSpecialized(): Unit = RefBenchmark.getAndUpdateSpecialized(10000)

  @Benchmark
  def modifyGeneric(): Unit = RefBenchmark.modifyGeneric(10000)

  @Benchmark
  def getAndUpdateGeneric(): Unit = RefBenchmark.getAndUpdateGeneric(10000)

}

object RefBenchmark {
  def modifySpecialized(iterations: Int): Unit = {
    Ref[IO].of(0L).flatMap { ref =>
      def loop(remaining: Int, acc: Long): IO[Long] = {
        if (remaining == 0) IO(acc)
        else ref.modify(n => (n + 1, n)).flatMap(prev => loop(remaining - 1, acc + prev))
      }
      loop(iterations, 0L)
    }
  }.void.unsafeRunSync()

  def getAndUpdateSpecialized(iterations: Int): Unit = {
    Ref[IO].of(0L).flatMap { ref =>
      def loop(remaining: Int, acc: Long): IO[Long] = {
        if (remaining == 0) IO(acc)
        else ref.getAndUpdate(_ + 1).flatMap(prev => loop(remaining - 1, acc + prev))
      }
      loop(iterations, 0L)
    }
  }.void.unsafeRunSync()

  def modifyGeneric(iterations: Int): Unit = {
    Ref[IO].of[Long](0L).flatMap { ref =>
      def loop(remaining: Int, acc: Long): IO[Long] = {
        if (remaining == 0) IO(acc)
        else ref.modify(n => (n + 1, n)).flatMap(prev => loop(remaining - 1, acc + prev))
      }
      loop(iterations, 0L)
    }
  }.void.unsafeRunSync()

  def getAndUpdateGeneric(iterations: Int): Unit = {
    Ref[IO].of[Long](0L).flatMap { ref =>
      def loop(remaining: Int, acc: Long): IO[Long] = {
        if (remaining == 0) IO(acc)
        else ref.getAndUpdate(_ + 1).flatMap(prev => loop(remaining - 1, acc + prev))
      }
      loop(iterations, 0L)
    }
  }.void.unsafeRunSync()

}
