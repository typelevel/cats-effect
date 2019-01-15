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

package cats
package effect

import cats.implicits._
import cats.effect.concurrent.Ref
import scala.concurrent.duration._
import scala.util.{Success}

import cats.effect.laws.discipline.arbitrary._
import cats.laws._
import cats.laws.discipline._

class MemoizeTests extends BaseTestsSuite {
  testAsync("Concurrent.memoize does not evaluates the effect if the inner `F[A]`isn't bound") { implicit ec =>
    implicit val cs = ec.contextShift[IO]
    val timer = ec.timer[IO]

    val prog = for {
      ref <- Ref.of[IO, Int](0)
      action = ref.update(_ + 1)
      _ <- Concurrent.memoize(action)
      _ <- timer.sleep(100.millis)
      v <- ref.get
    } yield v

    val result = prog.unsafeToFuture()
    ec.tick(200.millis)
    result.value shouldBe Some(Success(0))
  }

  testAsync("Concurrent.memoize evalutes effect once if inner `F[A]` is bound twice"){ implicit ec =>
    implicit val cs = ec.contextShift[IO]

    val prog = for {
      ref <- Ref.of[IO, Int](0)
      action = ref.modify { s =>
        val ns = s + 1
        ns -> ns
      }
      memoized <- Concurrent.memoize(action)
      x <- memoized
      y <- memoized
      v <- ref.get
    } yield (x, y, v)

    val result = prog.unsafeToFuture()
    ec.tick()
    result.value shouldBe Some(Success((1, 1, 1)))
  }

  testAsync("Concurrent.memoize effect evaluates effect once if the inner `F[A]` is bound twice (race)" ){ implicit ec =>
    implicit val cs = ec.contextShift[IO]
    val timer = ec.timer[IO]

    val prog = for {
      ref <- Ref.of[IO, Int](0)
      action = ref.modify { s =>
        val ns = s + 1
        ns -> ns
      }
      memoized <- Concurrent.memoize(action)
      _ <- memoized.start
      x <- memoized
      _ <- timer.sleep(100.millis)
      v <- ref.get
    } yield x -> v

    val result = prog.unsafeToFuture()
    ec.tick(200.millis)
    result.value shouldBe Some(Success((1, 1)))
  }

  testAsync("Concurrent.memoize and then flatten is identity") { implicit ec =>
    implicit val cs = ec.contextShift[IO]
    check { fa: IO[Int] =>
      Concurrent.memoize(fa).flatten <-> fa
    }
  }
}
