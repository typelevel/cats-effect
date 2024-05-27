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
package kernel

import cats.{Eq, Order, StackSafeMonad}
import cats.arrow.FunctionK
import cats.effect.laws.AsyncTests
import cats.effect.testkit.TestControl
import cats.effect.unsafe.IORuntimeConfig
import cats.laws.discipline.arbitrary._

import org.scalacheck.{Arbitrary, Cogen, Prop}
import org.scalacheck.Arbitrary.arbitrary
import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.{ExecutionContext, Promise}
import scala.concurrent.duration._

import java.util.concurrent.atomic.AtomicBoolean

class AsyncSpec extends BaseSpec with Discipline {

  // we just need this because of the laws testing, since the prop runs can interfere with each other
  sequential

  {
    implicit val ticker = Ticker()

    checkAll(
      "AsyncIO",
      AsyncTests[AsyncIO].async[Int, Int, Int](10.millis)
    ) /*(Parameters(seed = Some(Seed.fromBase64("ZxDXpm7_3Pdkl-Fvt8M90Cxfam9wKuzcifQ1QsIJxND=").get)))*/
  }

  "fromFuture" should {
    "backpressure on cancelation" in real {
      // a non-cancelable, never-completing Future
      def mkf() = Promise[Unit]().future

      def go = for {
        started <- IO(new AtomicBoolean)
        fiber <- IO.fromFuture {
          IO {
            started.set(true)
            mkf()
          }
        }.start
        _ <- IO.cede.whileM_(IO(!started.get))
        _ <- fiber.cancel
      } yield ()

      TestControl
        .executeEmbed(go, IORuntimeConfig(1, 2))
        .as(false)
        .recover { case _: TestControl.NonTerminationException => true }
        .replicateA(1000)
        .map(_.forall(identity(_)))
    }

  }

  "fromFutureCancelable" should {

    "cancel on fiber cancelation" in real {
      val smallDelay: IO[Unit] = IO.sleep(10.millis)
      def mkf() = Promise[Unit]().future

      val go = for {
        canceled <- IO(new AtomicBoolean)
        fiber <- IO.fromFutureCancelable {
          IO(mkf()).map(f => f -> IO(canceled.set(true)))
        }.start
        _ <- smallDelay
        _ <- fiber.cancel
        res <- IO(canceled.get() mustEqual true)
      } yield res

      TestControl.executeEmbed(go, IORuntimeConfig(1, 2)).replicateA(1000)

    }

    "backpressure on cancelation" in real {
      // a non-cancelable, never-completing Future
      def mkf() = Promise[Unit]().future

      val go = for {
        started <- IO(new AtomicBoolean)
        fiber <- IO.fromFutureCancelable {
          IO {
            started.set(true)
            mkf()
          }.map(f => f -> IO.never)
        }.start
        _ <- IO.cede.whileM_(IO(!started.get))
        _ <- fiber.cancel
      } yield ()

      TestControl
        .executeEmbed(go, IORuntimeConfig(1, 2))
        .as(false)
        .recover { case _: TestControl.NonTerminationException => true }
        .replicateA(1000)
        .map(_.forall(identity(_)))
    }

  }

  final class AsyncIO[A](val io: IO[A])

  implicit def asyncForAsyncIO: Async[AsyncIO] = new Async[AsyncIO]
    with StackSafeMonad[AsyncIO] {
    def pure[A](x: A): AsyncIO[A] = liftIO(IO.pure(x))
    def raiseError[A](e: Throwable): AsyncIO[A] = liftIO(IO.raiseError(e))
    def suspend[A](hint: Sync.Type)(thunk: => A): AsyncIO[A] = liftIO(IO.suspend(hint)(thunk))

    def canceled: AsyncIO[Unit] = liftIO(IO.canceled)
    def cede: AsyncIO[Unit] = liftIO(IO.cede)
    def executionContext: AsyncIO[ExecutionContext] = liftIO(IO.executionContext)

    def monotonic: AsyncIO[FiniteDuration] = liftIO(IO.monotonic)
    def realTime: AsyncIO[FiniteDuration] = liftIO(IO.realTime)

    def ref[A](a: A): AsyncIO[Ref[AsyncIO, A]] = delay(Ref.unsafe(a)(this))
    def deferred[A]: AsyncIO[Deferred[AsyncIO, A]] = delay(Deferred.unsafe(this))

    def evalOn[A](fa: AsyncIO[A], ec: ExecutionContext): AsyncIO[A] = wrapIO(fa)(_.evalOn(ec))

    def flatMap[A, B](fa: AsyncIO[A])(f: A => AsyncIO[B]): AsyncIO[B] =
      wrapIO(fa)(_.flatMap(f(_).io))

    def forceR[A, B](fa: AsyncIO[A])(fb: AsyncIO[B]): AsyncIO[B] = wrapIO(fa)(_.forceR(fb.io))

    def handleErrorWith[A](fa: AsyncIO[A])(f: Throwable => AsyncIO[A]): AsyncIO[A] =
      wrapIO(fa)(_.handleErrorWith(f(_).io))

    def onCancel[A](fa: AsyncIO[A], fin: AsyncIO[Unit]): AsyncIO[A] =
      wrapIO(fa)(_.onCancel(fin.io))

    def sleep(time: FiniteDuration): AsyncIO[Unit] = liftIO(IO.sleep(time))

    def cont[K, R](body: Cont[AsyncIO, K, R]): AsyncIO[R] = {
      val lower: FunctionK[AsyncIO, IO] = new FunctionK[AsyncIO, IO] {
        def apply[A](fa: AsyncIO[A]): IO[A] = fa.io
      }

      val ioCont = new Cont[IO, K, R] {
        def apply[G[_]](implicit G: MonadCancel[G, Throwable]) = { (resume, get, lift) =>
          body.apply[G].apply(resume, get, lower.andThen(lift))
        }
      }

      liftIO(IO.cont(ioCont))
    }

    def start[A](fa: AsyncIO[A]): AsyncIO[Fiber[AsyncIO, Throwable, A]] =
      liftIO(fa.io.start.map(liftFiber))

    def uncancelable[A](body: Poll[AsyncIO] => AsyncIO[A]): AsyncIO[A] =
      liftIO {
        IO.uncancelable { nat =>
          val natT = new Poll[AsyncIO] {
            def apply[B](aio: AsyncIO[B]): AsyncIO[B] = wrapIO(aio)(nat(_))
          }
          body(natT).io
        }
      }

    private val liftIO: FunctionK[IO, AsyncIO] = new FunctionK[IO, AsyncIO] {
      def apply[A](fa: IO[A]): AsyncIO[A] = new AsyncIO(fa)
    }

    private def liftOutcome[A](oc: Outcome[IO, Throwable, A]): Outcome[AsyncIO, Throwable, A] =
      oc match {
        case Outcome.Canceled() => Outcome.Canceled()
        case Outcome.Errored(e) => Outcome.Errored(e)
        case Outcome.Succeeded(foa) => Outcome.Succeeded(liftIO(foa))
      }

    private def liftFiber[A](fib: FiberIO[A]): Fiber[AsyncIO, Throwable, A] =
      new Fiber[AsyncIO, Throwable, A] {
        def cancel: AsyncIO[Unit] = liftIO(fib.cancel)
        def join: AsyncIO[Outcome[AsyncIO, Throwable, A]] = liftIO(fib.join.map(liftOutcome))
      }

    private def wrapIO[A, B](aio: AsyncIO[A])(f: IO[A] => IO[B]): AsyncIO[B] =
      liftIO(f(aio.io))

  }

  implicit def arbitraryAsyncIO[A](implicit arbIO: Arbitrary[IO[A]]): Arbitrary[AsyncIO[A]] =
    Arbitrary(arbitrary[IO[A]].map(new AsyncIO(_)))

  implicit def cogenOutcomeAsyncIO[A](
      implicit
      cogenOutcomeIO: Cogen[Outcome[IO, Throwable, A]]): Cogen[Outcome[AsyncIO, Throwable, A]] =
    cogenOutcomeIO.contramap {
      case Outcome.Canceled() => Outcome.Canceled()
      case Outcome.Errored(e) => Outcome.Errored(e)
      case Outcome.Succeeded(aio) => Outcome.Succeeded(aio.io)
    }

  implicit def eqAsyncIO[A](implicit eqIO: Eq[IO[A]]): Eq[AsyncIO[A]] = (x, y) =>
    eqIO.eqv(x.io, y.io)

  implicit def orderAsyncIO[A](implicit orderIO: Order[IO[A]]): Order[AsyncIO[A]] = (x, y) =>
    orderIO.compare(x.io, y.io)

  implicit def execAsyncIO[A](implicit execIO: IO[A] => Prop): AsyncIO[A] => Prop = aio =>
    execIO(aio.io)

}
