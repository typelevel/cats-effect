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

package cats.effect.benchmarks

import cats.effect.IO
import cats.effect.std.Random
import cats.effect.unsafe.implicits.global

import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

/**
 * To do comparative benchmarks between versions:
 *
 * benchmarks/run-benchmark RandomBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 * Jmh / run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.RandomBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class RandomBenchmark {

  @Param(Array("10000", "100000", "1000000"))
  var size: Int = _

  var list: List[Int] = _
  var vector: Vector[Int] = _
  var map: Map[String, Int] = _

  @Setup
  def setup(): Unit = {
    list = (1 to size).toList
    vector = (1 to size).toVector
    map = (1 to size).map(x => (x.toString, x)).toMap
  }

  val random: Random[IO] = Random.scalaUtilRandom[IO].unsafeRunSync()

  @Benchmark
  def elementOfList(): Int = {
    random.elementOf(list).unsafeRunSync()
  }

  @Benchmark
  def elementOfVector(): Int = {
    random.elementOf(vector).unsafeRunSync()
  }

  @Benchmark
  def elementOfMap(): (String, Int) = {
    random.elementOf(map).unsafeRunSync()
  }

}
