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

import cats.{Eq, Show}
import cats.effect.testkit.{TestContext, TestInstances}
import cats.effect.unsafe.IORuntime
import cats.syntax.all._

import org.scalacheck.Gen
import org.specs2.execute.AsResult
import org.specs2.matcher.Matcher
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.core.Execution

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.reflect.ClassTag

trait Runners extends SpecificationLike with TestInstances with RunnersPlatform { outer =>

  def executionTimeout = 20.seconds

  def ticked[A: AsResult](test: Ticker => A): Execution =
    Execution.result(test(Ticker(TestContext())))

  def real[A: AsResult](test: => IO[A]): Execution =
    Execution.withEnvAsync { _ =>
      val (fut, cancel) = test.unsafeToFutureCancelable()(runtime())
      timeout(fut, cancel, executionTimeout)
    }

  /*
   * Hacky implementation of effectful property testing
   */
  def realProp[A, B](gen: Gen[A])(f: A => IO[B])(implicit R: AsResult[List[B]]): Execution =
    real(List.range(1, 100).traverse { _ =>
      val a = gen.sample.get
      f(a)
    })

  def realWithRuntime[A: AsResult](test: IORuntime => IO[A]): Execution =
    Execution.withEnvAsync { _ =>
      val rt = runtime()
      val (fut, cancel) = test(rt).unsafeToFutureCancelable()(rt)
      timeout(fut, cancel, executionTimeout)
    }

  def completeAs[A: Eq: Show](expected: A)(implicit ticker: Ticker): Matcher[IO[A]] =
    tickTo(Outcome.Succeeded(Some(expected)))

  def completeAsSync[A: Eq: Show](expected: A): Matcher[SyncIO[A]] = { (ioa: SyncIO[A]) =>
    val a = ioa.unsafeRunSync()
    (a eqv expected, s"${a.show} !== ${expected.show}")
  }

  def failAs(expected: Throwable)(implicit ticker: Ticker): Matcher[IO[Unit]] =
    tickTo[Unit](Outcome.Errored(expected))

  def failAsSync[A](expected: Throwable): Matcher[SyncIO[A]] = { (ioa: SyncIO[A]) =>
    val t =
      (try ioa.unsafeRunSync()
      catch {
        case t: Throwable => t
      }).asInstanceOf[Throwable]
    (t eqv expected, s"${t.show} !== ${expected.show}")
  }

  def nonTerminate(implicit ticker: Ticker): Matcher[IO[Unit]] =
    tickTo[Unit](Outcome.Succeeded(None))

  def selfCancel(implicit ticker: Ticker): Matcher[IO[Unit]] =
    tickTo[Unit](Outcome.Canceled())

  def tickTo[A: Eq: Show](expected: Outcome[Option, Throwable, A])(
      implicit ticker: Ticker): Matcher[IO[A]] = { (ioa: IO[A]) =>
    val oc = unsafeRun(ioa)
    (oc eqv expected, s"${oc.show} !== ${expected.show}")
  }

  // useful for tests in the `real` context
  implicit class Assertions[A](fa: IO[A]) {
    def mustFailWith[E <: Throwable: ClassTag] =
      fa.attempt.flatMap { res =>
        IO {
          res must beLike { case Left(e) => e must haveClass[E] }
        }
      }

    def mustEqual(a: A) = fa.flatMap { res => IO(res must beEqualTo(a)) }
  }

  private def timeout[A](
      f: Future[A],
      cancel: () => Future[Unit],
      duration: FiniteDuration): Future[A] = {
    val p = Promise[A]()
    val r = runtime()
    implicit val ec = r.compute

    val cancelTimer =
      r.scheduler
        .sleep(
          duration,
          { () =>
            if (p.tryFailure(new TestTimeoutException)) {
              cancel()
              ()
            }
          })

    f.onComplete { result =>
      if (p.tryComplete(result)) {
        cancelTimer.run()
      }
    }

    p.future
  }
}

class TestTimeoutException extends Exception
