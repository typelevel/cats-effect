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
import org.scalatest.{AsyncFunSuite, EitherValues, Matchers}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class DeferredTests extends AsyncFunSuite with Matchers with EitherValues {

  implicit override def executionContext: ExecutionContext = ExecutionContext.Implicits.global

  trait DeferredConstructor { def apply[A]: IO[Deferred[IO, A]] }

  def tests(label: String, pc: DeferredConstructor): Unit = {

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
        _ <- IO.shift *> (modifyGate.get *> state.modify_(_ * 2) *> readGate.complete(())).start
        _ <- IO.shift *> (state.set(1) *> modifyGate.complete(())).start
        _ <- readGate.get
        res <- state.get
      } yield res
      op.unsafeToFuture.map(_ shouldBe 2)
    }
  }

  tests("concurrent", new DeferredConstructor { def apply[A] = Deferred[IO, A] })
  tests("async", new DeferredConstructor { def apply[A] = Deferred.async[IO, A] })

  private def cancelBeforeForcing(pc: IO[Deferred[IO, Int]]): IO[Option[Int]] = 
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
    cancelBeforeForcing(Deferred.apply).unsafeToFuture.map(_ shouldBe None)
  }

  test("async - get - cancel before forcing") {
    cancelBeforeForcing(Deferred.async).unsafeToFuture.map(_ shouldBe Some(42))
  }
}
