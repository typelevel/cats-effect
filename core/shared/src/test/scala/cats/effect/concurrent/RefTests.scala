/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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
package concurrent

import cats.implicits._
import org.scalatest.{AsyncFunSuite, Matchers, Succeeded}
import org.scalatest.compatible.Assertion
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class RefTests extends AsyncFunSuite with Matchers {

  implicit override def executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  private val smallDelay: IO[Unit] = timer.sleep(20.millis)

  private def awaitEqual[A: Eq](t: IO[A], success: A): IO[Unit] =
    t.flatMap(a => if (Eq[A].eqv(a, success)) IO.unit else smallDelay *> awaitEqual(t, success))

  private def run(t: IO[Unit]): Future[Assertion] = t.as(Succeeded).unsafeToFuture

  test("concurrent modifications") {
    val finalValue = 100
    val r = Ref.unsafe[IO, Int](0)
    val modifies = List.fill(finalValue)(IO.shift *> r.update(_ + 1)).sequence
    run(IO.shift *> modifies.start *> awaitEqual(r.get, finalValue))
  }

  test("access - successful") {
    val op = for {
      r <- Ref[IO].of(0)
      valueAndSetter <- r.access
      (value, setter) = valueAndSetter
      success <- setter(value + 1)
      result <- r.get
    } yield success && result == 1
    run(op.map(_ shouldBe true))
  }

  test("access - setter should fail if value is modified before setter is called") {
    val op = for {
      r <- Ref[IO].of(0)
      valueAndSetter <- r.access
      (value, setter) = valueAndSetter
      _ <- r.set(5)
      success <- setter(value + 1)
      result <- r.get
    } yield !success && result == 5
    run(op.map(_ shouldBe true))
  }

  test("access - setter should fail if called twice") {
    val op = for {
      r <- Ref[IO].of(0)
      valueAndSetter <- r.access
      (value, setter) = valueAndSetter
      cond1 <- setter(value + 1)
      _ <- r.set(value)
      cond2 <- setter(value + 1)
      result <- r.get
    } yield cond1 && !cond2 && result == 0
    run(op.map(_ shouldBe true))
  }
}
 
