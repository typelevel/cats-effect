/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

import java.util.concurrent._
import cats.effect.{ExitCode, IO, IOApp, Resource, SyncIO}
import org.openjdk.jmh.annotations._
import scala.concurrent.ExecutionContext

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class ECBenchmark {
  trait Run { self: IOApp =>
    val size = 100000
    def run(args: List[String]) = {
      val _ = args
      def loop(i: Int): IO[Int] =
        if (i < size) IO.shift.flatMap(_ => IO.pure(i + 1)).flatMap(loop)
        else IO.shift.flatMap(_ => IO.pure(i))

      IO(0).flatMap(loop).map(_ => ExitCode.Success)
    }
  }

  private val ioApp = new IOApp with Run
  private val ioAppCtx = new IOApp.WithContext with Run {
    protected def executionContextResource: Resource[SyncIO, ExecutionContext] =
      Resource.liftF(SyncIO.pure(ExecutionContext.Implicits.global))
  }

  @Benchmark
  def app(): Unit = {
    val _ = ioApp.main(Array.empty)
  }

  @Benchmark
  def appWithCtx(): Unit = {
    val _ = ioAppCtx.main(Array.empty)
  }
}
