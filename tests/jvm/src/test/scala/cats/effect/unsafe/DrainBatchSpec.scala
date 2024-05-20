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

package cats.effect
package unsafe

import cats.effect.std.Queue

class DrainBatchSpec extends BaseSpec {

  "Batch draining" should {
    "work correctly in the presence of concurrent stealers" in real {
      val iterations = 500000

      def catsEffectRepeat[A](n: Int)(io: IO[A]): IO[A] =
        if (n <= 1) io
        else io.flatMap(_ => catsEffectRepeat(n - 1)(io))

      def iterate(deferred: Deferred[IO, Unit], n: Int): IO[Any] =
        for {
          ref <- IO.ref(n)
          queue <- Queue.bounded[IO, Unit](1)
          effect = queue.offer(()).start >>
            queue.take >>
            ref.modify(n => (n - 1, if (n == 1) deferred.complete(()) else IO.unit)).flatten
          _ <- catsEffectRepeat(iterations)(effect.start)
        } yield ()

      for {
        deferred <- IO.deferred[Unit]
        _ <- iterate(deferred, iterations).start
        _ <- deferred.get
        res <- IO(ok)
      } yield res
    }
  }
}
