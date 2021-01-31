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

import cats.effect.IO
import cats.effect.unsafe._
import cats.syntax.all._

import scala.concurrent.ExecutionContext

import java.util.concurrent.{Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.openjdk.jmh.annotations._

/**
 * To do comparative benchmarks between versions:
 *
 *     benchmarks/run-benchmark WorkStealingBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 *     jmh:run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.WorkStealingBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread".
 * Please note that benchmarks should be usually executed at least in
 * 10 iterations (as a rule of thumb), but more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MINUTES)
class WorkStealingBenchmark {

  @Param(Array("1000000"))
  var size: Int = _

  def schedulingBenchmark(implicit runtime: IORuntime): Int = {
    def fiber(i: Int): IO[Int] =
      IO.cede.flatMap { _ =>
        IO(i).flatMap { j =>
          IO.cede.flatMap { _ =>
            if (j > 10000)
              IO.cede.flatMap(_ => IO.pure(j))
            else
              IO.cede.flatMap(_ => fiber(j + 1))
          }
        }
      }

    List
      .range(0, size)
      .traverse(fiber(_).start)
      .flatMap(_.traverse(_.joinWithNever))
      .map(_.sum)
      .unsafeRunSync()
  }

  @Benchmark
  def scheduling(): Int = {
    import cats.effect.unsafe.implicits.global
    schedulingBenchmark
  }

  def allocBenchmark(implicit runtime: IORuntime): Int = {
    def allocation(n: Int): IO[Array[AnyRef]] =
      IO {
        val size = math.max(100, math.min(n, 2000))
        val array = new Array[AnyRef](size)
        for (i <- (0 until size)) {
          array(i) = new AnyRef()
        }
        array
      }

    def sum(array: Array[AnyRef]): IO[Int] =
      IO {
        array.map(_.hashCode()).sum
      }

    def fiber(i: Int): IO[Int] =
      IO.cede.flatMap { _ =>
        allocation(i).flatMap { arr =>
          IO.cede.flatMap(_ => sum(arr)).flatMap { _ =>
            if (i > 1000)
              IO.cede.flatMap(_ => IO.pure(i))
            else
              IO.cede.flatMap(_ => fiber(i + 1))
          }
        }
      }

    List
      .range(0, 2500)
      .traverse(_ => fiber(0).start)
      .flatMap(_.traverse(_.joinWithNever))
      .map(_.sum)
      .unsafeRunSync()
  }

  @Benchmark
  def alloc(): Int = {
    import cats.effect.unsafe.implicits.global
    allocBenchmark
  }

  lazy val manyThreadsRuntime: IORuntime = {
    val (blocking, blockDown) = {
      val threadCount = new AtomicInteger(0)
      val executor = Executors.newCachedThreadPool { (r: Runnable) =>
        val t = new Thread(r)
        t.setName(s"io-blocking-${threadCount.getAndIncrement()}")
        t.setDaemon(true)
        t
      }
      (ExecutionContext.fromExecutor(executor), () => executor.shutdown())
    }

    val (scheduler, schedDown) = {
      val executor = Executors.newSingleThreadScheduledExecutor { r =>
        val t = new Thread(r)
        t.setName("io-scheduler")
        t.setDaemon(true)
        t.setPriority(Thread.MAX_PRIORITY)
        t
      }
      (Scheduler.fromScheduledExecutor(executor), () => executor.shutdown())
    }

    val compute = new WorkStealingThreadPool(256, "io-compute", manyThreadsRuntime)

    val cancellationCheckThreshold =
      System.getProperty("cats.effect.cancellation.check.threshold", "512").toInt

    new IORuntime(
      compute,
      blocking,
      scheduler,
      () => (),
      IORuntimeConfig(
        cancellationCheckThreshold,
        System
          .getProperty("cats.effect.auto.yield.threshold.multiplier", "2")
          .toInt * cancellationCheckThreshold
      ),
      internalShutdown = () => {
        compute.shutdown()
        blockDown()
        schedDown()
      }
    )
  }

  @Benchmark
  def manyThreadsSchedulingBenchmarks(): Int = {
    implicit val runtime = manyThreadsRuntime
    schedulingBenchmark
  }
}
