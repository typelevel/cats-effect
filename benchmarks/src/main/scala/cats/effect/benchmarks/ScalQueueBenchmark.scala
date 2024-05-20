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

import cats.effect.unsafe.ScalQueue

import org.openjdk.jmh.annotations._

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, ThreadLocalRandom, TimeUnit}

/**
 * To do comparative benchmarks between versions:
 *
 * benchmarks/run-benchmark WorkStealingBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 * Jmh / run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.WorkStealingBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MINUTES)
class ScalQueueBenchmark {

  @Param(Array("131072")) // 2^17
  var size: Int = _

  @Param(Array("4")) // keep this a power of 2
  var threads: Int = _

  val thing = new AnyRef

  @Benchmark
  def scalConcurrentEnqueueDequeue(): Unit = {
    val q = new ScalQueue[AnyRef](threads)
    val latch = new CountDownLatch(threads)

    // every thread will send and receive this number of events
    val limit = size / threads
    val batch = threads * 4

    0.until(threads) foreach { _ =>
      val t = new Thread({ () =>
        val random = ThreadLocalRandom.current()

        var j = 0
        while (j < limit / batch) {
          var i = 0
          while (i < batch) {
            q.offer(thing, random)
            i += 1
          }

          i = 0
          while (i < batch) {
            var v: AnyRef = null
            while (v == null) {
              v = q.poll(random)
            }

            i += 1
          }

          j += 1
        }

        latch.countDown()
      })

      t.start()
    }

    latch.await()
  }

  @Benchmark
  def clqConcurrentEnqueueDequeue(): Unit = {
    val q = new ConcurrentLinkedQueue[AnyRef]()
    val latch = new CountDownLatch(threads)

    // every thread will send and receive this number of events
    val limit = size / threads
    val batch = threads * 4

    0.until(threads) foreach { _ =>
      val t = new Thread({ () =>
        var j = 0
        while (j < limit / batch) {
          var i = 0
          while (i < batch) {
            q.offer(thing)
            i += 1
          }

          i = 0
          while (i < batch) {
            var v: AnyRef = null
            while (v == null) {
              v = q.poll()
            }

            i += 1
          }

          j += 1
        }

        latch.countDown()
      })

      t.start()
    }

    latch.await()
  }
}
