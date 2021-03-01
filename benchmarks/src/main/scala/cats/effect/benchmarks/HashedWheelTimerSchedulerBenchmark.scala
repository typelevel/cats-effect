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
import cats.effect.unsafe.{HashedWheelTimerScheduler, IORuntime, Scheduler}
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

  implicit lazy val runtime: IORuntime = {
    val (compute, compDown) = IORuntime.createDefaultComputeThreadPool(runtime)
    val (blocking, blockDown) = IORuntime.createDefaultBlockingExecutionContext()
    val s = new HashedWheelTimerScheduler(HashedWheelTimerScheduler.defaultWheelSize, 5.millis)
    val (scheduler, schedDown) = (s, { () => s.shutdown() })
    // val (scheduler, schedDown) = Scheduler.createOldScheduler()

    IORuntime(
      compute,
      blocking,
      scheduler,
      () => {
        compDown() //
        blockDown()
        schedDown()
      }
    )
  }

  val tokens: Int = 100

  @Param(Array("1000000"))
  var size: Int = _

  @Benchmark
  def schedule() = {

    val run =
      for {
        fs <- List.range(0, size).traverse { i =>
          (IO.sleep(i.micros) >> IO(Blackhole.consumeCPU(tokens))).start
        }
        _ <- fs.traverse(_.join)
      } yield ()

    run.unsafeRunSync()

  }

}
