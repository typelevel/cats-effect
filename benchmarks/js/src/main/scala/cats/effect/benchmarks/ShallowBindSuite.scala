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

import japgolly.scalajs.benchmark.Suite
import japgolly.scalajs.benchmark.Benchmark
import japgolly.scalajs.benchmark.Plan

object ShallowBindSuite {

  lazy val plan = Plan(suite, Vector(10000))

  lazy val suite = Suite[Int]("shallow bind")(
    Benchmark.fromFn("pure") { size =>
      def loop(i: Int): IO[Int] =
        if (i < size) IO.pure(i + 1).flatMap(loop)
        else IO.pure(i)

      IO.pure(0).flatMap(loop).void
    },
    Benchmark.fromFn("delay") { size =>
      def loop(i: Int): IO[Int] =
        if (i < size) IO(i + 1).flatMap(loop)
        else IO(i)

      IO(0).flatMap(loop).void
    },
    Benchmark.fromFn("async") { size =>
      def loop(i: Int): IO[Int] =
        if (i < size) IO.cede.flatMap(_ => IO.pure(i + 1)).flatMap(loop)
        else IO.cede.flatMap(_ => IO.pure(i))

      IO(0).flatMap(loop).void
    }
  )

}
