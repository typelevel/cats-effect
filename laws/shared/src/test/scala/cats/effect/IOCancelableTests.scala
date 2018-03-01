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

package cats.effect

import cats.effect.internals.Callback
import cats.effect.laws.discipline.arbitrary._
import cats.implicits._
import cats.laws._
import cats.laws.discipline._
import org.scalacheck.Prop

import scala.concurrent.Promise
import scala.util.Success

class IOCancelableTests extends BaseTestsSuite {
  testAsync("IO.cancelBoundary <-> IO.unit") { implicit ec =>
    val f = IO.cancelBoundary.unsafeToFuture()
    f.value shouldBe Some(Success(()))
  }

  testAsync("IO.cancelBoundary can be cancelled") { implicit ec =>
    val f = (IO.shift *> IO.cancelBoundary).unsafeToFuture()
    f.value shouldBe None
    ec.tick()
    f.value shouldBe Some(Success(()))
  }

  testAsync("fa *> IO.cancelBoundary <-> fa") { implicit ec =>
    Prop.forAll { (fa: IO[Int]) =>
      fa <* IO.cancelBoundary <-> fa
    }
  }

  testAsync("(fa *> IO.cancelBoundary).cancel <-> IO.never") { implicit ec =>
    Prop.forAll { (fa: IO[Int]) =>
      val received =
        for {
          f <- (fa <* IO.cancelBoundary).start
          _ <- f.cancel
          a <- f.join
        } yield a

      received <-> IO.async(_ => ())
    }
  }

  testAsync("fa.onCancelRaiseError(e) <-> fa") { implicit ec =>
    Prop.forAll { (fa: IO[Int], e: Throwable) =>
      fa.onCancelRaiseError(e) <-> fa
    }
  }

  testAsync("(fa *> IO.cancelBoundary).onCancelRaiseError(e).cancel <-> IO.raiseError(e)") { implicit ec =>
    Prop.forAll { (fa: IO[Int], e: Throwable) =>
      val received =
        for {
          f <- (fa <* IO.cancelBoundary).onCancelRaiseError(e).start
          _ <- f.cancel
          a <- f.join
        } yield a

      received <-> IO.raiseError(e)
    }
  }

  testAsync("uncancelable") { implicit ec =>
    Prop.forAll { (fa: IO[Int]) =>
      val received =
        for {
          f <- (fa <* IO.cancelBoundary).uncancelable.start
          _ <- f.cancel
          a <- f.join
        } yield a

      received <-> fa
    }
  }

  testAsync("task.start.flatMap(id) <-> task") { implicit sc =>
    Prop.forAll { (task: IO[Int]) =>
      task.start.flatMap(_.join) <-> task
    }
  }

  testAsync("task.start is cancelable") { implicit sc =>
    val task = (IO.shift *> IO.cancelBoundary *> IO(1)).start.flatMap(_.join)

    val p = Promise[Int]()
    val cancel = task.unsafeRunCancelable(Callback.promise(p))
    sc.state.tasks.isEmpty shouldBe false

    cancel()
    sc.tick()
    sc.state.tasks.isEmpty shouldBe true
    p.future.value shouldBe None
  }
}
