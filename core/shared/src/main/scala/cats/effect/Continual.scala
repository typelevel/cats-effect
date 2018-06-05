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

import cats.Monoid
import cats.data._
import cats.effect.ExitCase.Canceled
import simulacrum.typeclass

/**
 * Describes [[Sync]] data types capable of the "continual"
 * evaluation model.
 *
 * In the continual evaluation model `flatMap` chains are not
 * auto-cancelable, the underlying run-loop relying solely on user
 * supplied cancelation logic (e.g. [[Concurrent.cancelable]]).
 *
 * Describes [[cats.effect.Continual!.continual continual]],
 * see its description for the contract.
 */
@typeclass trait Continual[F[_]] extends Serializable {
  /**
   * [[Sync]] restriction for types that can implement `Continual`.
   *
   * Using composition instead of inheritance, because the
   * `Continual` type class is an add-on to the type class hierarchy.
   *
   * Note that using inheritance instead of composition would make it
   * difficult to work with `Continual` in combination with other
   * sub-types of `Sync`, such as `Async` or `Concurrent`.
   */
  implicit val sync: Sync[F]

  /**
   * Returns an `F[A]` value that on evaluation ensures the source
   * will be evaluated using the "continual" model.
   *
   * In the continual evaluation model `flatMap` chains are not
   * auto-cancelable, the underlying run-loop relying solely on
   * user supplied cancelation logic (e.g. [[Concurrent.cancelable]]).
   *
   * Example:
   *
   * {{{
   *   val F: Continual[F] = ???
   *   val C: Continual[F] = ???
   *   val timer: Timer[F] = Timer.derive[F]
   *
   *   val task = F.cancelable[Int] { _ =>
   *     // N.B. this is a task that will never terminate
   *     // (callback never gets invoked), but that has a
   *     // cancelation handler installed:
   *     IO(println("Cancelled task"))
   *   }
   *
   *   C.continual {
   *     timer.shift.flatMap(_ => task)
   *   }
   * }}}
   *
   * In this sample we are guaranteed that the cancelation handler
   * of the described `task` gets evaluated, printing "cancelled task1",
   * because `timer.shift` is not cancelable and the `flatMap` that
   * binds them is not cancelable.
   *
   * Another example:
   *
   * {{{
   *   C.continual {
   *     timer.sleep(1.second).flatMap(_ => task)
   *   }
   * }}}
   *
   * This time `timer.sleep(1.second)` is cancelable and in case
   * the cancel signal is received by the run-loop within that one
   * second, then it will get cancelled. However, if
   * `timer.sleep(1.second)` finishes, emitting its tick, then it's
   * guaranteed that the cancelation handler of `task` gets executed.
   *
   * In other words if you have:
   *
   * {{{
   *   for (t1 <- task1; t2 <- task2) yield ()
   * }}}
   *
   * In the "continual" evaluation model you can have either "task1"
   * cancelled (and its cancelation handler triggered), or "task2",
   * but the implementation can no longer interrupt the processing
   * in between.
   *
   * In other words the contract is that:
   *
   *  - if `task1` finishes, `task2` is guaranteed to at least start
   *  - cancelation of a bind chain can still happen, but is managed
   *    solely by user supplied logic (e.g. [[Concurrent.cancelable]])
   *
   * For [[cats.effect.IO]] this function would be the identity
   * function, since Cats-Effect's `IO` is already using the
   * "continual" model in its implementation.
   */
  def continual[A](fa: F[A]): F[A]

  /**
   * Returns a new `F` value that mirrors the source for normal
   * termination, but that triggers the given error on cancelation.
   *
   * This `onCancelRaiseError` operator transforms any task into one
   * that on cancelation will terminate with the given error, thus
   * transforming potentially non-terminating tasks into ones that
   * yield a certain error.
   *
   * {{{
   *   import scala.concurrent.CancellationException
   *
   *   val F = Continual[IO]
   *   val timer = Timer[IO]
   *
   *   val error = new CancellationException("Boo!")
   *   val fa = F.onCancelRaiseError(timer.sleep(5.seconds), error)
   *
   *   fa.start.flatMap { fiber =>
   *     fiber.cancel *> fiber.join
   *   }
   * }}}
   *
   * Without "onCancelRaiseError" the [[Timer.sleep sleep]] operation
   * yields a non-terminating task on cancellation. But by applying
   * "onCancelRaiseError", the yielded task above will terminate with
   * the indicated "CancellationException" reference, which we can
   * then also distinguish from other errors thrown in the `F` context.
   *
   * Depending on the implementation, tasks that are canceled can
   * become non-terminating. This operation ensures that when
   * cancelation happens, the resulting task is terminated with an
   * error, such that logic can be scheduled to happen after
   * cancelation:
   *
   * {{{
   *   import scala.concurrent.CancellationException
   *   val wasCanceled = new CancellationException()
   *
   *   F.onCancelRaiseError(fa, wasCanceled).attempt.flatMap {
   *     case Right(a) =>
   *       F.delay(println(s"Success: \$a"))
   *     case Left(`wasCanceled`) =>
   *       F.delay(println("Was canceled!"))
   *     case Left(error) =>
   *       F.delay(println(s"Terminated in error: \$error"))
   *   }
   * }}}
   *
   * This technique is how a "bracket" operation can be implemented
   * in the "continual" model.
   *
   * Besides sending the cancelation signal, this operation also cuts
   * the connection between the producer and the consumer. Example:
   *
   * {{{
   *   val F = Concurrent[IO]
   *   val timer = Timer[IO]
   *
   *   // This task is uninterruptible ;-)
   *   val tick = F.uncancelable(
   *     for {
   *       _ <- timer.sleep(5.seconds)
   *       _ <- IO(println("Tick!"))
   *     } yield ())
   *
   *   // Builds an value that triggers an exception on cancellation
   *   val loud = F.onCancelRaiseError(tick, new CancellationException)
   * }}}
   *
   * In this example the `loud` reference will be completed with a
   * "CancellationException", as indicated via "onCancelRaiseError".
   * The logic of the source won't get cancelled, because we've
   * embedded it all in [[Bracket.uncancelable uncancelable]]. But
   * its bind continuation is not allowed to continue after that, its
   * final result not being allowed to be signaled.
   *
   * Therefore this also transforms `uncancelable` values into ones
   * that can be canceled. The logic of the source, its run-loop
   * might not be interruptible, however `cancel` on a value on which
   * `onCancelRaiseError` was applied will cut the connection from
   * the producer, the consumer receiving the indicated error instead.
   */
  def onCancelRaiseError[A](fa: F[A], e: Throwable): F[A] =
    sync.guaranteeCase(fa) {
      case Canceled => sync.raiseError(e)
      case _ => sync.unit
    }
}

object Continual {
  /**
   * [[Continual]] instance built for `cats.data.EitherT` values initialized
   * with any `F` data type that also implements `Continual`.
   */
  implicit def catsEitherTContinual[F[_]: Continual, L]: Continual[EitherT[F, L, ?]] =
    new EitherTContinual[F, L] { def F = Continual[F] }

  /**
   * [[Continual]] instance built for `cats.data.OptionT` values initialized
   * with any `F` data type that also implements `Continual`.
   */
  implicit def catsOptionTContinual[F[_]: Continual]: Continual[OptionT[F, ?]] =
    new OptionTContinual[F] { def F = Continual[F] }

  /**
   * [[Continual]] instance built for `cats.data.StateT` values initialized
   * with any `F` data type that also implements `Continual`.
   */
  implicit def catsStateTContinual[F[_]: Continual, S]: Continual[StateT[F, S, ?]] =
    new StateTContinual[F, S] { def F = Continual[F] }

  /**
   * [[Continual]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `Continual`.
   */
  implicit def catsWriterTContinual[F[_]: Continual, L: Monoid]: Continual[WriterT[F, L, ?]] =
    new WriterTContinual[F, L] { def F = Continual[F]; def L = Monoid[L] }

  /**
   * [[Continual]] instance built for `cats.data.Kleisli` values initialized
   * with any `F` data type that also implements `Continual`.
   */
  implicit def catsKleisliContinual[F[_]: Continual, R]: Continual[Kleisli[F, R, ?]] =
    new KleisliContinual[F, R] { def F = Continual[F] }

  private[effect] trait EitherTContinual[F[_], L] extends Continual[EitherT[F, L, ?]] {
    protected implicit def F: Continual[F]

    val sync: Sync[EitherT[F, L, ?]] = Sync.catsEitherTSync(F.sync)

    override def continual[A](fa: EitherT[F, L, A]): EitherT[F, L, A] =
      EitherT(F.continual(fa.value))

    override def onCancelRaiseError[A](fa: EitherT[F, L, A], e: Throwable): EitherT[F, L, A] =
      EitherT(F.onCancelRaiseError(fa.value, e))
  }

  private[effect] trait OptionTContinual[F[_]] extends Continual[OptionT[F, ?]] {
    protected implicit def F: Continual[F]

    val sync: Sync[OptionT[F, ?]] = Sync.catsOptionTSync(F.sync)

    override def continual[A](fa: OptionT[F, A]): OptionT[F, A] =
      OptionT(F.continual(fa.value))

    override def onCancelRaiseError[A](fa: OptionT[F, A], e: Throwable): OptionT[F, A] =
      OptionT(F.onCancelRaiseError(fa.value, e))
  }

  private[effect] trait StateTContinual[F[_], S] extends Continual[StateT[F, S, ?]] {
    protected implicit def F: Continual[F]
    private implicit def FF: Sync[F] = F.sync

    val sync: Sync[StateT[F, S, ?]] = Sync.catsStateTSync(F.sync)

    override def continual[A](fa: StateT[F, S, A]): StateT[F, S, A] =
      StateT.applyF(F.continual(fa.runF))

    override def onCancelRaiseError[A](fa: StateT[F, S, A], e: Throwable): StateT[F, S, A] =
      fa.transformF(F.onCancelRaiseError(_, e))
  }

  private[effect] trait WriterTContinual[F[_], L] extends Continual[WriterT[F, L, ?]] {
    protected implicit def F: Continual[F]
    protected implicit def L: Monoid[L]

    val sync: Sync[WriterT[F, L, ?]] = Sync.catsWriterTSync(F.sync, L)

    override def continual[A](fa: WriterT[F, L, A]): WriterT[F, L, A] =
      WriterT(F.continual(fa.run))

    override def onCancelRaiseError[A](fa: WriterT[F, L, A], e: Throwable): WriterT[F, L, A] =
      WriterT(F.onCancelRaiseError(fa.run, e))
  }

  private[effect] abstract class KleisliContinual[F[_], R] extends Continual[Kleisli[F, R, ?]] {
    protected implicit def F: Continual[F]

    val sync: Sync[Kleisli[F, R, ?]] = Sync.catsKleisliSync(F.sync)

    override def continual[A](fa: Kleisli[F, R, A]): Kleisli[F, R, A] =
      Kleisli(a => F.sync.suspend(F.continual(fa.run(a))))

    override def onCancelRaiseError[A](fa: Kleisli[F, R, A], e: Throwable): Kleisli[F, R, A] =
      Kleisli(a => F.sync.suspend(F.onCancelRaiseError(fa.run(a), e)))
  }
}
