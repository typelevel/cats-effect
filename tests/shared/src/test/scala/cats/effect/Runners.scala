/*
 * Copyright 2020-2025 Typelevel
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

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.reflect.{classTag, ClassTag}

import munit.{FunSuite, Location, TestOptions}
import munit.internal.PlatformCompat

trait Runners extends TestInstances with RunnersPlatform {
  self: FunSuite =>

  def executionTimeout: FiniteDuration = 20.seconds
  override def munitTimeout: Duration = executionTimeout

  def ticked(options: TestOptions)(body: Ticker => Unit)(implicit loc: Location): Unit =
    test(options)(body(Ticker(TestContext())))

  def real(options: TestOptions)(body: => IO[Unit])(implicit loc: Location): Unit =
    test(options) {
      val (fut, cancel) = body.unsafeToFutureCancelable()(runtime())
      timeout(fut, cancel, executionTimeout)
    }

  /*
   * Hacky implementation of effectful property testing
   */
  def realProp[A](options: TestOptions, gen: Gen[A])(f: A => IO[Unit])(
      implicit loc: Location): Unit =
    real(options)(List.range(1, 100).traverse_ { _ =>
      val a = gen.sample.get
      f(a)
    })

  def realWithRuntime(options: TestOptions)(f: IORuntime => IO[Unit])(
      implicit loc: Location): Unit =
    test(options) {
      val rt = runtime()
      val (fut, cancel) = f(rt).unsafeToFutureCancelable()(rt)
      timeout(fut, cancel, executionTimeout)
    }

  def assertEqv[A: Eq: Show](obtained: A, expected: A)(implicit loc: Location): Unit = {
    implicit val comp: munit.Compare[A, A] = (l, r) => Eq[A].eqv(l, r)
    assertEquals(obtained, expected, show"$obtained !== $expected")
  }

  def assertCompleteAs[A: Eq: Show](ioa: IO[A], expected: A)(
      implicit ticker: Ticker,
      loc: Location): Unit =
    assertTickTo(ioa, Outcome.Succeeded(Some(expected)))

  def assertCompleteAsSync[A: Eq: Show](ioa: SyncIO[A], expected: A)(
      implicit loc: Location): Unit = {
    val a = ioa.unsafeRunSync()
    assert(a eqv expected, s"${a.show} !== ${expected.show}")
  }

  def assertFailAs(io: IO[Unit], expected: Throwable)(
      implicit ticker: Ticker,
      loc: Location): Unit =
    assertTickTo[Unit](io, Outcome.Errored(expected))

  def assertFailAsSync[A](ioa: SyncIO[A], expected: Throwable)(implicit loc: Location): Unit = {
    val t =
      (try ioa.unsafeRunSync()
      catch {
        case t: Throwable => t
      }).asInstanceOf[Throwable]
    assert(t eqv expected, s"${t.show} !== ${expected.show}")
  }

  def assertNonTerminate(io: IO[Unit])(implicit ticker: Ticker, loc: Location): Unit =
    assertTickTo[Unit](io, Outcome.Succeeded(None))

  def assertSelfCancel(io: IO[Unit])(implicit ticker: Ticker, loc: Location): Unit =
    assertTickTo[Unit](io, Outcome.Canceled())

  def assertTickTo[A: Eq: Show](ioa: IO[A], expected: Outcome[Option, Throwable, A])(
      implicit ticker: Ticker,
      loc: Location): Unit = {
    val oc = unsafeRun(ioa)
    assert(oc eqv expected, s"${oc.show} !== ${expected.show}")
  }

  implicit class TestOptionsSyntax(options: TestOptions) {
    // todo: munit 1.0 has `options.pending`
    def pendingNative: TestOptions = if (PlatformCompat.isNative) options.ignore else options
    def ignoreNative: TestOptions = if (PlatformCompat.isNative) options.ignore else options
    def ignoreJS: TestOptions = if (PlatformCompat.isJS) options.ignore else options
  }

  // useful for tests in the `real` context
  implicit class Assertions[A](fa: IO[A]) {
    def mustFailWith[E <: Throwable: ClassTag](implicit loc: Location): IO[Unit] =
      fa.attempt.flatMap { res =>
        IO {
          res match {
            case Left(e) => assert(classTag[E].runtimeClass.isAssignableFrom(e.getClass))
            case Right(value) =>
              fail(
                s"Expected Left(${classTag[E].runtimeClass.getSimpleName}) got Right($value)")
          }
        }
      }

    def mustEqual(a: A)(implicit loc: Location): IO[Unit] =
      fa.flatMap { res => IO(assertEquals(res, a)) }
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
