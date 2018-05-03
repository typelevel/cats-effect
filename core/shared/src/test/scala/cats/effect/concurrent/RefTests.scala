/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2013-2018 Paul Chiusano, and respective contributors 
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
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

  private val smallDelay: IO[Unit] = Timer[IO].sleep(20.millis)

  private def awaitEqual[A: Eq](t: IO[A], success: A): IO[Unit] =
    t.flatMap(a => if (Eq[A].eqv(a, success)) IO.unit else smallDelay *> awaitEqual(t, success))

  private def run(t: IO[Unit]): Future[Assertion] = t.as(Succeeded).unsafeToFuture

  test("concurrent modifications") {
    val finalValue = 100
    val r = Ref.unsafeCreate[IO, Int](0)
    val modifies = List.fill(finalValue)(IO.shift *> r.modify(c => (c + 1, ()))).sequence
    run(IO.shift *> modifies.start *> awaitEqual(r.get, finalValue))
  }

  test("access - successful") {
    val op = for {
      r <- Ref[IO, Int](0)
      valueAndSetter <- r.access
      (value, setter) = valueAndSetter
      success <- setter(value + 1)
      result <- r.get
    } yield success && result == 1
    run(op.map(_ shouldBe true))
  }

  test("access - setter should fail if value is modified before setter is called") {
    val op = for {
      r <- Ref[IO, Int](0)
      valueAndSetter <- r.access
      (value, setter) = valueAndSetter
      _ <- r.setSync(5)
      success <- setter(value + 1)
      result <- r.get
    } yield !success && result == 5
    run(op.map(_ shouldBe true))
  }

  test("access - setter should fail if called twice") {
    val op = for {
      r <- Ref[IO, Int](0)
      valueAndSetter <- r.access
      (value, setter) = valueAndSetter
      cond1 <- setter(value + 1)
      _ <- r.setSync(value)
      cond2 <- setter(value + 1)
      result <- r.get
    } yield cond1 && !cond2 && result == 0
    run(op.map(_ shouldBe true))
  }
}
 
