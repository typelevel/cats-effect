/*
 * Copyright 2020 Typelevel
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

import cats.{Applicative, Eq, Order, Show}
import cats.effect.testkit.{
  AsyncGenerators,
  GenK,
  OutcomeGenerators,
  SyncGenerators,
  TestContext
}
import cats.syntax.all._

import org.scalacheck.{Arbitrary, Cogen, Gen, Prop}, Arbitrary.arbitrary

import org.specs2.execute.AsResult
import org.specs2.matcher.Matcher
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.core.Execution

import scala.annotation.implicitNotFound
import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}
import scala.concurrent.duration._
import scala.util.Try

import java.util.concurrent.TimeUnit

trait Runners extends SpecificationLike with RunnersPlatform { outer =>
  import OutcomeGenerators._

  def ticked[A: AsResult](test: Ticker => A): Execution =
    Execution.result(test(Ticker(TestContext())))

  def real[A: AsResult](test: => IO[A]): Execution =
    realWithTimeout(10.seconds)(test)

  def realWithTimeout[A: AsResult](duration: FiniteDuration)(test: => IO[A]): Execution =
    Execution.withEnvAsync(_ => timeout(test.unsafeToFuture()(runtime()), duration))

  implicit def cogenIO[A: Cogen](implicit ticker: Ticker): Cogen[IO[A]] =
    Cogen[Outcome[Option, Throwable, A]].contramap(unsafeRun(_))

  implicit def arbitraryIO[A: Arbitrary: Cogen](implicit ticker: Ticker): Arbitrary[IO[A]] = {
    val generators =
      new AsyncGenerators[IO] {

        val arbitraryE: Arbitrary[Throwable] =
          arbitraryThrowable

        val cogenE: Cogen[Throwable] = Cogen[Throwable]

        val F: Async[IO] = IO.effectForIO

        def cogenCase[B: Cogen]: Cogen[OutcomeIO[B]] =
          OutcomeGenerators.cogenOutcome[IO, Throwable, B]

        val arbitraryEC: Arbitrary[ExecutionContext] = outer.arbitraryEC

        val cogenFU: Cogen[IO[Unit]] = cogenIO[Unit]

        // TODO dedup with FreeSyncGenerators
        val arbitraryFD: Arbitrary[FiniteDuration] = outer.arbitraryFD

        override def recursiveGen[B: Arbitrary: Cogen](deeper: GenK[IO]) =
          super
            .recursiveGen[B](deeper)
            .filterNot(
              _._1 == "racePair"
            ) // remove the racePair generator since it reifies nondeterminism, which cannot be law-tested
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
        outer.arbitraryFD

      val F: Sync[SyncIO] =
        SyncIO.syncEffectForSyncIO
    }

    Arbitrary(generators.generators[A])
  }

  implicit def arbitraryResource[F[_], A](
      implicit F: Applicative[F],
      AFA: Arbitrary[F[A]],
      AFU: Arbitrary[F[Unit]]): Arbitrary[Resource[F, A]] =
    Arbitrary(Gen.delay(genResource[F, A]))

  implicit def arbitraryResourceParallel[F[_], A](
      implicit A: Arbitrary[Resource[F, A]]
  ): Arbitrary[Resource.Par[F, A]] =
    Arbitrary(A.arbitrary.map(Resource.Par.apply))

  // Consider improving this a strategy similar to Generators.
  def genResource[F[_], A](
      implicit F: Applicative[F],
      AFA: Arbitrary[F[A]],
      AFU: Arbitrary[F[Unit]]): Gen[Resource[F, A]] = {
    def genAllocate: Gen[Resource[F, A]] =
      for {
        alloc <- arbitrary[F[A]]
        dispose <- arbitrary[F[Unit]]
      } yield Resource(alloc.map(a => a -> dispose))

    def genBind: Gen[Resource[F, A]] =
      genAllocate.map(_.flatMap(a => Resource.pure[F, A](a)))

    def genSuspend: Gen[Resource[F, A]] =
      genAllocate.map(r => Resource.suspend(r.pure[F]))

    Gen.frequency(
      5 -> genAllocate,
      1 -> genBind,
      1 -> genSuspend
    )
  }

  implicit lazy val arbitraryFD: Arbitrary[FiniteDuration] = {
    import TimeUnit._

    val genTU = Gen.oneOf(NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS)

    Arbitrary {
      genTU flatMap { u => Gen.choose[Long](0L, 48L).map(FiniteDuration(_, u)) }
    }
  }

  implicit lazy val arbitraryThrowable: Arbitrary[Throwable] =
    Arbitrary(Arbitrary.arbitrary[Int].map(TestException))

  implicit def arbitraryEC(implicit ticker: Ticker): Arbitrary[ExecutionContext] =
    Arbitrary(Gen.const(ticker.ctx.derive()))

  implicit lazy val eqThrowable: Eq[Throwable] =
    Eq.fromUniversalEquals[Throwable]

  implicit lazy val shThrowable: Show[Throwable] =
    Show.fromToString[Throwable]

  implicit lazy val eqEC: Eq[ExecutionContext] =
    Eq.fromUniversalEquals[ExecutionContext]

  implicit def ordIOFD(implicit ticker: Ticker): Order[IO[FiniteDuration]] =
    Order by { ioa => unsafeRun(ioa).fold(None, _ => None, fa => fa) }

  implicit def eqIOA[A: Eq](implicit ticker: Ticker): Eq[IO[A]] =
    Eq.by(unsafeRun(_))
  /*Eq instance { (left: IO[A], right: IO[A]) =>
      val leftR = unsafeRun(left)
      val rightR = unsafeRun(right)

      val back = leftR eqv rightR

      if (!back) {
        println(s"$left != $right")
        println(s"$leftR != $rightR")
      }

      back
    }*/

  /**
   * Defines equality for a `Resource`.  Two resources are deemed
   * equivalent if they allocate an equivalent resource.  Cleanup,
   * which is run purely for effect, is not considered.
   */
  implicit def eqResource[F[_], A](
      implicit E: Eq[F[A]],
      F: Resource.Bracket[F]): Eq[Resource[F, A]] =
    new Eq[Resource[F, A]] {
      def eqv(x: Resource[F, A], y: Resource[F, A]): Boolean =
        E.eqv(x.use(F.pure), y.use(F.pure))
    }

  /**
   * Defines equality for `Resource.Par`.  Two resources are deemed
   * equivalent if they allocate an equivalent resource.  Cleanup,
   * which is run purely for effect, is not considered.
   */
  implicit def eqResourcePar[F[_], A](implicit E: Eq[Resource[F, A]]): Eq[Resource.Par[F, A]] =
    new Eq[Resource.Par[F, A]] {
      import Resource.Par.unwrap
      def eqv(x: Resource.Par[F, A], y: Resource.Par[F, A]): Boolean =
        E.eqv(unwrap(x), unwrap(y))
    }

  def unsafeRunSyncIOEither[A](io: SyncIO[A]): Either[Throwable, A] =
    Try(io.unsafeRunSync()).toEither

  implicit def eqSyncIOA[A: Eq]: Eq[SyncIO[A]] =
    Eq.instance { (left, right) =>
      unsafeRunSyncIOEither(left) === unsafeRunSyncIOEither(right)
    }

  // feel the rhythm, feel the rhyme...
  implicit def boolRunnings(iob: IO[Boolean])(implicit ticker: Ticker): Prop =
    Prop(unsafeRun(iob).fold(false, _ => false, _.getOrElse(false)))

  implicit def boolRunningsSync(iob: SyncIO[Boolean]): Prop =
    Prop {
      try iob.unsafeRunSync()
      catch {
        case _: Throwable => false
      }
    }

  def completeAs[A: Eq: Show](expected: A)(implicit ticker: Ticker): Matcher[IO[A]] =
    tickTo(Outcome.Completed(Some(expected)))

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
    tickTo[Unit](Outcome.Completed(None))

  def tickTo[A: Eq: Show](expected: Outcome[Option, Throwable, A])(
      implicit ticker: Ticker): Matcher[IO[A]] = { (ioa: IO[A]) =>
    val oc = unsafeRun(ioa)
    (oc eqv expected, s"${oc.show} !== ${expected.show}")
  }

  def unsafeRun[A](ioa: IO[A])(implicit ticker: Ticker): Outcome[Option, Throwable, A] =
    try {
      var results: Outcome[Option, Throwable, A] = Outcome.Completed(None)

      ioa.unsafeRunAsync {
        case Left(t) => results = Outcome.Errored(t)
        case Right(a) => results = Outcome.Completed(Some(a))
      }(unsafe.IORuntime(ticker.ctx, ticker.ctx, scheduler, () => ()))

      ticker.ctx.tickAll(3.days)

      /*println("====================================")
      println(s"completed ioa with $results")
      println("====================================")*/

      results
    } catch {
      case t: Throwable =>
        t.printStackTrace()
        throw t
    }

  implicit def materializeRuntime(implicit ticker: Ticker): unsafe.IORuntime =
    unsafe.IORuntime(ticker.ctx, ticker.ctx, scheduler, () => ())

  def scheduler(implicit ticker: Ticker): unsafe.Scheduler =
    new unsafe.Scheduler {
      import ticker.ctx

      def sleep(delay: FiniteDuration, action: Runnable): Runnable = {
        val cancel = ctx.schedule(delay, action)
        new Runnable { def run() = cancel() }
      }

      def nowMillis() = ctx.now().toMillis
      def monotonicNanos() = ctx.now().toNanos
    }

  private def timeout[A](f: Future[A], duration: FiniteDuration): Future[A] = {
    val p = Promise[A]()
    val r = runtime()
    implicit val ec = r.compute

    val cancel = r.scheduler.sleep(duration, { () => p.tryFailure(new TimeoutException); () })

    f.onComplete { result =>
      p.tryComplete(result)
      cancel.run()
    }

    p.future
  }

  @implicitNotFound(
    "could not find an instance of Ticker; try using `in ticked { implicit ticker =>`")
  case class Ticker(ctx: TestContext)
}
