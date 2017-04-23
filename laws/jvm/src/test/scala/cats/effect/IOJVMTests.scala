/*
 * Copyright 2017 Typelevel
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

import org.scalatest._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class IOJVMTests extends FunSuite with Matchers {
  val ThreadName = "test-thread"

  val TestEC = new ExecutionContext {
    def execute(r: Runnable): Unit = {
      val th = new Thread(r)
      th.setName(ThreadName)
      th.start()
    }

    def reportFailure(cause: Throwable): Unit =
      throw cause
  }

  test("shift contiguous prefix and suffix, but not interfix") {
    val name: IO[String] = IO { Thread.currentThread().getName }

    val aname: IO[String] = IO async { cb =>
      new Thread {
        start()
        override def run() =
          cb(Right(Thread.currentThread().getName))
      }
    }

    val test = for {
      n1 <- name
      n2 <- name
      n3 <- aname
      n4 <- name
      n5 <- name.shift(TestEC)
      n6 <- name
    } yield (n1, n2, n3, n4, n5, n6)

    val (n1, n2, n3, n4, n5, n6) = test.shift(TestEC).unsafeRunSync()

    n1 shouldEqual ThreadName
    n2 shouldEqual ThreadName
    n3 should not equal ThreadName
    n4 should not equal ThreadName
    n5 shouldEqual ThreadName
    n6 shouldEqual ThreadName
  }

  test("unsafeRunTimed(Duration.Undefined) throws exception") {
    val never = IO.async[Int](_ => ())

    intercept[IllegalArgumentException] {
      never.unsafeRunTimed(Duration.Undefined)
    }
  }

  test("unsafeRunTimed times-out on unending IO") {
    val never = IO.async[Int](_ => ())
    val start = System.currentTimeMillis()
    val received = never.unsafeRunTimed(100.millis)
    val elapsed = System.currentTimeMillis() - start

    assert(received === None)
    assert(elapsed >= 100)
  }

  // this is expected behavior
  // ...also it appears to fell the mighty Travis, so it's disabled for now
  /*test("fail to provide stack safety with repeated async suspensions") {
    val result = (0 until 10000).foldLeft(IO(0)) { (acc, i) =>
      acc.flatMap(n => IO.async[Int](_(Right(n + 1))))
    }

    intercept[StackOverflowError] {
      result.unsafeRunAsync(_ => ())
    }
  }*/
}
