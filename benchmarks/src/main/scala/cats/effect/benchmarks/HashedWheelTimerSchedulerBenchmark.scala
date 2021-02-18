/*
 * Copyright 2020-2021 Typelevel
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

import cats.implicits._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import scala.concurrent.duration._

import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.infra.Blackhole

/**
 * To do comparative benchmarks between versions:
 *
 *     benchmarks/run-benchmark HashedWheelTimerSchedulerBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 *     jmh:run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.HashedWheelTimerSchedulerBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread".
 * Please note that benchmarks should be usually executed at least in
 * 10 iterations (as a rule of thumb), but more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class HashedWheelTimerSchedulerBenchmark {

  val tokens: Int = 1000000

  @Param(Array("1000"))
  var size: Int = _

  //Measure performance of scheduling by measuring its impact on a pure CPU workload
  //This is necessary as different schedulers should always complete
  //in the same amount of time but consume different amounts of CPU to achieve it
  @Benchmark
  def schedule() = {
    val processors = Runtime.getRuntime().availableProcessors()
    val compute = List.range(0, processors).traverse { _ => IO(Blackhole.consumeCPU(tokens)).start}
    val timers = List.range(0, size).traverse { i => IO.sleep(i.millis).start }

    val run =
      for {
        cs <- compute
        ts <- timers
        _ <- ts.traverse(_.join)
        _ <- IO.println("timers done")
        _ <- cs.traverse(_.join)
        _ <- IO.println("compute  done")
      } yield ()

    run.unsafeRunSync()

  }

}
