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

package cats.effect
package benchmarks

import cats.syntax.all._

import japgolly.scalajs.benchmark.engine.Blackhole
import japgolly.scalajs.benchmark.Suite
import japgolly.scalajs.benchmark.Benchmark
import japgolly.scalajs.benchmark.Plan

object ParallelSuite {

  lazy val plan = Plan(suite, params)

  case class Params(size: Int, cpuTokens: Int)
  lazy val params = (for {
    size <- List(100, 1000)
    cpuTokens <- List(100, 1000, 10000)
    if size * cpuTokens <= 1000000
  } yield Params(size, cpuTokens)).toVector

  lazy val blackhole = new Blackhole

  lazy val suite = Suite[Params]("parallel")(
    Benchmark.fromFn("parTraverse") {
      case Params(size, cpuTokens) =>
        1.to(size).toList.parTraverse(_ => IO(blackhole.consumeCPU(cpuTokens))).void
    },
    Benchmark.fromFn("traverse") {
      case Params(size, cpuTokens) =>
        1.to(size).toList.traverse(_ => IO(blackhole.consumeCPU(cpuTokens))).void
    }
  )

}
