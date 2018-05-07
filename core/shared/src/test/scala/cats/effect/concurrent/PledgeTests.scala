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
import org.scalatest.{AsyncFunSuite, EitherValues, Matchers}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class PledgeTests extends AsyncFunSuite with Matchers with EitherValues {

  implicit override def executionContext: ExecutionContext = ExecutionContext.Implicits.global

  trait PledgeConstructor { def apply[A]: IO[Pledge[IO, A]] }

  def tests(label: String, pc: PledgeConstructor): Unit = {

    test(s"$label - complete") {
      pc[Int].flatMap { p =>
        p.complete(0) *> p.get
      }.unsafeToFuture.map(_ shouldBe 0)
    }

    test(s"$label - complete is only successful once") {
      pc[Int].flatMap { p =>
        p.complete(0) *> p.complete(1).attempt product p.get
      }.unsafeToFuture.map { case (err, value) =>
        err.left.value shouldBe an[IllegalStateException]
        value shouldBe 0
      }
    }

    test(s"$label - get blocks until set") {
      val op = for {
        state <- Ref[IO, Int](0)
        modifyGate <- pc[Unit]
        readGate <- pc[Unit]
        _ <- IO.shift *> (modifyGate.get *> state.modify(_ * 2) *> readGate.complete(())).start
        _ <- IO.shift *> (state.set(1) *> modifyGate.complete(())).start
        _ <- readGate.get
        res <- state.get
      } yield res
      op.unsafeToFuture.map(_ shouldBe 2)
    }
  }

  tests("concurrent", new PledgeConstructor { def apply[A] = Pledge[IO, A] })
  tests("async", new PledgeConstructor { def apply[A] = Pledge.async[IO, A] })

  private def cancelBeforeForcing(pc: IO[Pledge[IO, Int]]): IO[Option[Int]] = 
    for {
      r <- Ref[IO,Option[Int]](None)
        p <- pc
        fiber <- p.get.start
        _ <- fiber.cancel
        _ <- (IO.shift *> fiber.join.flatMap(i => r.set(Some(i)))).start
        _ <- Timer[IO].sleep(100.millis)
        _ <- p.complete(42)
        _ <- Timer[IO].sleep(100.millis)
        result <- r.get
      } yield result

  test("concurrent - get - cancel before forcing") {
    cancelBeforeForcing(Pledge.apply).unsafeToFuture.map(_ shouldBe None)
  }

  test("async - get - cancel before forcing") {
    cancelBeforeForcing(Pledge.async).unsafeToFuture.map(_ shouldBe Some(42))
  }
}
