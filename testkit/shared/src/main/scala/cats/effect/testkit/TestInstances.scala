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
package testkit

import cats.{~>, Applicative, Eq, Id, Order, Show}
import cats.effect.kernel.testkit.{
  AsyncGenerators,
  AsyncGeneratorsWithoutEvalShift,
  GenK,
  OutcomeGenerators,
  ParallelFGenerators,
  SyncGenerators,
  SyncTypeGenerators,
  TestInstances => KernelTestkitTestInstances
}
import cats.syntax.all._

import org.scalacheck.{Arbitrary, Cogen, Gen, Prop}

import scala.annotation.implicitNotFound
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.TimeUnit

trait TestInstances extends ParallelFGenerators with OutcomeGenerators with SyncTypeGenerators {
  outer =>

  implicit def cogenIO[A: Cogen](implicit ticker: Ticker): Cogen[IO[A]] =
    Cogen[Outcome[Option, Throwable, A]].contramap(unsafeRun(_))

  implicit def arbitraryIO[A: Arbitrary: Cogen](implicit ticker: Ticker): Arbitrary[IO[A]] = {
    val generators =
      new AsyncGenerators[IO] {

        val arbitraryE: Arbitrary[Throwable] =
          arbitraryThrowable

        val cogenE: Cogen[Throwable] = Cogen[Throwable]

        val F: Async[IO] = IO.asyncForIO

        def cogenCase[B: Cogen]: Cogen[OutcomeIO[B]] =
          OutcomeGenerators.cogenOutcome[IO, Throwable, B]

        val arbitraryEC: Arbitrary[ExecutionContext] = outer.arbitraryExecutionContext

        val cogenFU: Cogen[IO[Unit]] = cogenIO[Unit]

        // TODO dedup with FreeSyncGenerators
        val arbitraryFD: Arbitrary[FiniteDuration] = outer.arbitraryFiniteDuration

        override def recursiveGen[B: Arbitrary: Cogen](deeper: GenK[IO]) =
          super
            .recursiveGen[B](deeper)
            .filterNot(
              _._1 == "racePair"
            ) // remove the racePair generator since it reifies nondeterminism, which cannot be law-tested
      }

    Arbitrary(generators.generators[A])
  }

  def arbitraryIOWithoutContextShift[A: Arbitrary: Cogen]: Arbitrary[IO[A]] = {
    val generators = new AsyncGeneratorsWithoutEvalShift[IO] {
      override implicit val F: Async[IO] = IO.asyncForIO
      override implicit protected val arbitraryFD: Arbitrary[FiniteDuration] =
        outer.arbitraryFiniteDuration
      override implicit val arbitraryE: Arbitrary[Throwable] = outer.arbitraryThrowable
      override val cogenE: Cogen[Throwable] = Cogen[Throwable]

      override def recursiveGen[B: Arbitrary: Cogen](deeper: GenK[IO]) =
        super
          .recursiveGen[B](deeper)
          .filterNot(x =>
            x._1 == "evalOn" || x._1 == "racePair") // todo: enable racePair after MVar been made serialization compatible
    }

    Arbitrary(generators.generators[A])
  }

  implicit def arbitrarySyncIO[A: Arbitrary: Cogen]: Arbitrary[SyncIO[A]] = {
    val generators = new SyncGenerators[SyncIO] {
      val arbitraryE: Arbitrary[Throwable] =
        arbitraryThrowable

      val cogenE: Cogen[Throwable] =
        Cogen[Throwable]

      protected val arbitraryFD: Arbitrary[FiniteDuration] =
        outer.arbitraryFiniteDuration

      val F: Sync[SyncIO] =
        SyncIO.syncForSyncIO
    }

    Arbitrary(generators.generators[A])
  }

  implicit def arbitraryResource[F[_], A](
      implicit F: Applicative[F],
      AFA: Arbitrary[F[A]],
      AFU: Arbitrary[F[Unit]],
      AA: Arbitrary[A]
  ): Arbitrary[Resource[F, A]] =
    KernelTestkitInstances.arbitraryResource[F, A]

  // Consider improving this a strategy similar to Generators.
  // Doesn't include interruptible resources
  def genResource[F[_], A](
      implicit F: Applicative[F],
      AFA: Arbitrary[F[A]],
      AFU: Arbitrary[F[Unit]],
      AA: Arbitrary[A]
  ): Gen[Resource[F, A]] =
    KernelTestkitInstances.genResource[F, A]

  implicit lazy val arbitraryFiniteDuration: Arbitrary[FiniteDuration] = {
    import TimeUnit._

    val genTU = Gen.oneOf(NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS)

    Arbitrary {
      genTU flatMap { u => Gen.choose[Long](0L, 48L).map(FiniteDuration(_, u)) }
    }
  }

  // Note: do not make this implicit as it is not necessary and pollutes implicit scope of downstream projects
  lazy val arbitraryThrowable: Arbitrary[Throwable] =
    Arbitrary(Arbitrary.arbitrary[Int].map(TestException(_)))

  implicit def arbitraryExecutionContext(implicit ticker: Ticker): Arbitrary[ExecutionContext] =
    Arbitrary(Gen.const(ticker.ctx.derive()))

  implicit lazy val eqThrowable: Eq[Throwable] =
    Eq.fromUniversalEquals[Throwable]

  implicit lazy val showThrowable: Show[Throwable] =
    Show.fromToString[Throwable]

  implicit lazy val eqExecutionContext: Eq[ExecutionContext] =
    Eq.fromUniversalEquals[ExecutionContext]

  implicit def orderIoFiniteDuration(implicit ticker: Ticker): Order[IO[FiniteDuration]] =
    Order by { ioa => unsafeRun(ioa).fold(None, _ => None, fa => fa) }

  implicit def eqIOA[A: Eq](implicit ticker: Ticker): Eq[IO[A]] =
    Eq.by(unsafeRun(_))

  /**
   * Defines equality for a `Resource`. Two resources are deemed equivalent if they allocate an
   * equivalent resource. Cleanup, which is run purely for effect, is not considered.
   */
  implicit def eqResource[F[_], A](
      implicit E: Eq[F[A]],
      F: MonadCancel[F, Throwable]): Eq[Resource[F, A]] =
    KernelTestkitInstances.eqResource[F, A]

  implicit def ordResourceFFD[F[_]](
      implicit ordF: Order[F[FiniteDuration]],
      F: MonadCancel[F, Throwable]): Order[Resource[F, FiniteDuration]] =
    Order.by(_.use(_.pure[F]))

  implicit def eqSyncIOA[A: Eq]: Eq[SyncIO[A]] =
    Eq.by(unsafeRunSyncSupressedError)

  implicit def ioBooleanToProp(iob: IO[Boolean])(implicit ticker: Ticker): Prop =
    Prop(unsafeRun(iob).fold(false, _ => false, _.getOrElse(false)))

  implicit def syncIoBooleanToProp(iob: SyncIO[Boolean]): Prop =
    Prop {
      try iob.unsafeRunSync()
      catch {
        case _: Throwable => false
      }
    }

  implicit def resourceFBooleanToProp[F[_]](r: Resource[F, Boolean])(
      implicit view: F[Boolean] => Prop,
      F: MonadCancel[F, Throwable]): Prop =
    view(r.use(_.pure[F]))

  private val someK: Id ~> Option =
    new ~>[Id, Option] { def apply[A](a: A) = a.some }

  def unsafeRun[A](ioa: IO[A])(implicit ticker: Ticker): Outcome[Option, Throwable, A] =
    try {
      var results: Outcome[Option, Throwable, A] = Outcome.Succeeded(None)

      ioa
        .flatMap(IO.pure(_))
        .handleErrorWith(IO.raiseError(_))
        .unsafeRunAsyncOutcome { oc => results = oc.mapK(someK) }(materializeRuntime)

      ticker.ctx.tickAll()

      /*println("====================================")
      println(s"completed ioa with $results")
      println("====================================")*/

      results
    } catch {
      case t: Throwable =>
        t.printStackTrace()
        throw t
    }

  def unsafeRunSync[A](ioa: SyncIO[A]): Outcome[Id, Throwable, A] =
    try Outcome.succeeded[Id, Throwable, A](ioa.unsafeRunSync())
    catch {
      case t: Throwable => Outcome.errored(t)
    }

  private def unsafeRunSyncSupressedError[A](ioa: SyncIO[A]): Outcome[Id, Throwable, A] = {
    val old = System.err
    val err = new PrintStream(new ByteArrayOutputStream())
    try {
      System.setErr(err)
      unsafeRunSync(ioa)
    } finally {
      System.setErr(old)
    }
  }

  implicit def materializeRuntime(implicit ticker: Ticker): unsafe.IORuntime =
    unsafe.IORuntime.testRuntime(ticker.ctx, scheduler)

  def scheduler(implicit ticker: Ticker): unsafe.Scheduler = {
    val ctx = ticker.ctx
    new unsafe.Scheduler {
      def sleep(delay: FiniteDuration, action: Runnable): Runnable = {
        val cancel = ctx.schedule(delay, action)
        new Runnable { def run() = cancel() }
      }

      def nowMillis() = ctx.now().toMillis
      override def nowMicros(): Long = ctx.now().toMicros
      def monotonicNanos() = ctx.now().toNanos
    }
  }

  @implicitNotFound(
    "could not find an instance of Ticker; try using `in ticked { implicit ticker =>`")
  case class Ticker(ctx: TestContext = TestContext())
}

/**
 * This object exists for the keeping binary compatibility in the `TestInstances` trait by
 * forwarding to the implementaions coming from `cats-effect-kernel-testkit`.
 *
 * This object must exist as a standalone top-level object because any attempt to hide it inside
 * the `TestInstances` trait ends up generating synthetic forwarder methods which break binary
 * compatibility.
 */
private object KernelTestkitInstances extends KernelTestkitTestInstances
