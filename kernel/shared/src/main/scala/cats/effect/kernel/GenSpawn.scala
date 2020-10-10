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

package cats.effect.kernel

import cats.~>
import cats.data.{EitherT, Ior, IorT, Kleisli, OptionT, WriterT}
import cats.{Monoid, Semigroup}
import cats.syntax.all._

/**
 * A typeclass that characterizes monads which support spawning and racing of
 * fibers. [[GenSpawn]] extends the capabilities of [[MonadCancel]], so an
 * instance of this typeclass must also provide a lawful instance for
 * [[MonadCancel]].
 *
 * This documentation builds upon concepts introduced in the [[MonadCancel]]
 * documentation.
 *
 * ==Concurrency==
 *
 * [[GenSpawn]] introduces a notion of concurrency that enables fibers to
 * safely interact with each other via three special functions.
 * [[GenSpawn!.start start]] spawns a fiber that executes concurrently with the
 * spawning fiber. [[Fiber!.join join]] semantically blocks the joining fiber
 * until the joinee fiber terminates, after which the outcome of the joinee is
 * returned. [[Fiber!.cancel cancel]] requests a fiber to abnormally terminate,
 * and semantically blocks the canceller until the cancellee has completed
 * finalization.
 *
 * Just like threads, fibers can execute concurrently with respect to
 * each other. This means that the effects of independent fibers may be
 * interleaved nondeterministically. This mode of concurrency reaps benefits
 * for modular program design; fibers that are described separately can execute
 * simultaneously without requiring programmers to explicitly yield back to the
 * runtime system.
 *
 * The interleaving of effects is illustrated in the following program:
 *
 * {{{
 *
 *   for {
 *     fa <- (println("A1") *> println("A2")).start
 *     fb <- (println("B1") *> println("B2")).start
 *   } yield ()
 *
 * }}}
 *
 * In this program, two fibers A and B are spawned concurrently. There are six
 * possible executions, each of which exhibits a different ordering of effects.
 * The observed output of each execution is shown below:
 *
 *   1. A1, A2, B1, B2
 *   2. A1, B1, A2, B2
 *   3. A1, B1, B2, A2
 *   4. B1, B2, A1, A2
 *   5. B1, A1, B2, A2
 *   6. B1, A1, A2, B3
 *
 * Notice how every execution preserves sequential consistency of the effects
 * within each fiber: `A1` always prints before `A2`, and `B1` always prints
 * before `B2`. However, there are no guarantees around how the effects of
 * both fibers will be ordered with respect to each other; it is entirely
 * nondeterministic.
 *
 * ==Cancellation==
 *
 * [[MonadCancel]] introduces a simple means of cancellation, particularly
 * self-cancellation, where a fiber can request the abnormally terminatation of
 * its own execution. This is achieved by calling
 * [[MonadCancel!.canceled canceled]].
 *
 * [[GenSpawn]] expands on the cancellation model described by [[MonadCancel]]
 * by introducing a means of external cancellation. With external cancellation,
 * a fiber can request the abnormal termination of another fiber by calling
 * [[Fiber!.cancel]].
 *
 * The cancellation model dictates that external cancellation behaves
 * identically to self-cancellation. To guarantee consistent behavior between
 * the two, the following semantics are shared:
 *
 *   1. Masking: if a fiber is cancelled while it is masked, cancellation is
 *      suppressed until it reaches a completely unmasked state. See
 *      [[MonadCancel]] documentation for more details.
 *   1. Backpressure: [[Fiber!.cancel cancel]] semantically blocks all callers
 *      until finalization is complete.
 *   1. Idempotency: once a fiber's cancellation has been requested, subsequent
 *      cancellations have no effect on cancellation status.
 *   1. Terminal: Cancellation of a fiber that has terminated immediately
 *      returns.
 *
 * External cancellation contrasts with self-cancellation in one aspect: the
 * former may require synchronization between multiple threads to communicate
 * a cancellation request. As a result, cancellation may not be immediately
 * observed by a fiber. Implementations are free to decide how and when this
 * synchronization takes place.
 *
 * ==Cancellation safety==
 *
 * A function or effect is considered to be cancellation-safe if it can be run
 * in the absence of masking without violating effectful lifecycles or leaking
 * resources. These functions require extra attention and care from users to
 * ensure safe usage.
 *
 * [[start]] and [[racePair]] are both considered to be cancellation-unsafe
 * effects because they return a [[Fiber]], which is a resource that has a
 * lifecycle.
 *
 * {{{
 *
 *   // Start a fiber that continuously prints "A".
 *   // After 10 seconds, cancel the fiber.
 *   F.start(F.delay(println("A")).foreverM).flatMap { fiber =>
 *     F.sleep(10.seconds) *> fiber.cancel
 *   }
 *
 * }}}
 *
 * In the above example, imagine the spawning fiber is cancelled after it
 * starts the printing fiber, but before the latter is cancelled. In this
 * situation, the printing fiber is cancelled and will continue executing
 * forever, contending with other fibers for system resources. This is known
 * as a fiber leak, which is similar to a thread leak.
 *
 * Resource leaks like this are unfavorable when writing applications. In
 * the case of [[start]] and [[racePair]], it is recommended against using
 * these methods in favor of [[background]] and [[race]] respectively.
 *
 * The following example depicts a safer version of the `start` example
 * above:
 *
 * {{{
 *
 *   // Starts a fiber that continously prints "A".
 *   // After 10 seconds, the resource scope exits so the fiber is cancelled.
 *   F.background(F.delay(println("A")).foreverM).use { _ =>
 *     F.sleep(10.seconds)
 *   }
 *
 * }}}
 *
 * ==Scheduling==
 *
 * Fibers are commonly referred to as *lightweight threads* or *green threads*.
 * This alludes to the nature by which fibers are schdeuled by runtime systems:
 * many fibers are multiplexed onto one or more native threads.
 *
 * For applications running on the JVM, the scheduler typically manages a thread
 * pool onto which fibers are scheduled. These fibers are executed
 * simultaneously by the threads in the pool, achieving both concurrency and
 * parallelism. For applications running on JavaScript platforms, all compute
 * is restricted to a single worker thread, so multiple fibers must share that
 * worker thread (dictated by fairness properties), achieving concurrency.
 *
 * [[GenSpawn!.cede cede]] is a special function that interacts directly with
 * the underlying scheduler. It is a means of cooperative multitasking by which
 * a fiber signals to the runtime system that it intends to pause execution and
 * resume  at some later time at the discretion of the scheduler. This is in
 * contrast to preemptive multitasking, in which threads of control are forcibly
 * yielded after a well-defined time slice.
 *
 * Preemptive and cooperative multitasking are both features of runtime systems
 * that influence the fairness and throughput properties of an application.
 * These modes of scheduling are not necessarily mutually exclusive: a runtime
 * system may incorporate a blend of the two, where users may yield at their
 * own discretion, but the runtime may preempt a fiber if it has not yielded
 * for some time.
 *
 * For more details on schedulers, refer to the following resources:
 *
 *   1. https://gist.github.com/djspiewak/3ac3f3f55a780e8ab6fa2ca87160ca40
 *   1. https://gist.github.com/djspiewak/46b543800958cf61af6efa8e072bfd5c
 */
trait GenSpawn[F[_], E] extends MonadCancel[F, E] {

  /**
   * A low-level primitive for starting the concurrent evaluation of a fiber.
   * Returns a [[Fiber]] that can be used to wait for a fiber or cancel it.
   *
   * [[start]] is a cancellation-unsafe function; it is recommended to
   * use the safer variant [[background]] to spawn fibers.
   *
   * @param fa the effect for the fiber
   *
   * @see [[background]] for the safer, recommended variant
   */
  def start[A](fa: F[A]): F[Fiber[F, E, A]]

  /**
   * Returns a [[Resource]] that manages the concurrent execution of a fiber.
   * The inner resource can be used to wait on the outcome of the underlying
   * fiber; it is effectively a [[Fiber!.join join]].
   *
   * When the [[Resource]] goes out of scope or if the spawning fiber is
   * cancelled, the underlying fiber is cancelled.
   *
   * {{{
   *
   *   // Starts a fiber that continously prints "A".
   *   // After 10 seconds, the resource scope exits so the fiber is cancelled.
   *   F.background(F.delay(println("A")).foreverM).use { _ =>
   *     F.sleep(10.seconds)
   *   }
   *
   * }}}
   *
   * @param fa the effect for the spawned fiber
   */
  def background[A](fa: F[A]): Resource[F, F[Outcome[F, E, A]]] =
    Resource.make(start(fa))(_.cancel)(this).map(_.join)(this)

  /**
   * A non-terminating effect that never completes, which causes a fiber to
   * semantically block indefinitely. This is the purely functional,
   * asynchronous equivalent of an infinite while loop in Java, but no
   * native threads are blocked.
   *
   * A fiber that is suspended in [[never]] can be cancelled if it is
   * completely unmasked before it suspends:
   *
   * {{{
   *
   *   // ignoring race conditions between `start` and `cancel`
   *   F.never.start.flatMap(_.cancel) <-> F.unit
   *
   * }}}
   *
   * However, if the fiber is masked, cancellers will be semantically blocked
   * forever:
   *
   * {{{
   *
   *   // ignoring race conditions between `start` and `cancel`
   *   F.uncancelable(_ => F.never).start.flatMap(_.cancel) <-> F.never
   *
   * }}}
   */
  def never[A]: F[A]

  /**
   * Introduces a fairness boundary that yields control back to the scheduler
   * of the runtime system. This allows the carrier thread to resume execution
   * of another waiting fiber.
   *
   * Note that `cede` is merely a hint to the runtime system; implementations
   * have the liberty to interpret this method to their liking as long as it
   * obeys the respective laws. For example, a lawful, but atypical,
   * implementation of this function is `F.unit`, in which case the fairness
   * boundary is a no-op.
   */
  def cede: F[Unit]

  /**
   * A low-level primitive for racing the evaluation of two fibers that returns
   * the [[Outcome]] of the winner and the [[Fiber]] of the loser. The winner
   * of the race is considered to be the first fiber that completes with an
   * outcome.
   *
   * [[racePair]] is a cancellation-unsafe function; it is recommended to use
   * the safer variants.
   *
   * @param fa the effect for the first racing fiber
   * @param fb the effect for the second racing fiber
   *
   * @see [[raceOutcome]] and [[race]] for safer race variants.
   */
  def racePair[A, B](fa: F[A], fb: F[B])
      : F[Either[(Outcome[F, E, A], Fiber[F, E, B]), (Fiber[F, E, A], Outcome[F, E, B])]]

  /**
   * Races the evaluation of two fibers that returns the [[Outcome]] of the
   * winner. The winner of the race is considered to be the first fiber that
   * completes with an outcome. The loser of the race is cancelled before
   * returning.
   *
   * @param fa the effect for the first racing fiber
   * @param fb the effect for the second racing fiber
   *
   * @see [[race]] for a simpler variant that returns the successful outcome.
   */
  def raceOutcome[A, B](fa: F[A], fb: F[B]): F[Either[Outcome[F, E, A], Outcome[F, E, B]]] =
    uncancelable { _ =>
      flatMap(racePair(fa, fb)) {
        case Left((oc, f)) => as(f.cancel, Left(oc))
        case Right((f, oc)) => as(f.cancel, Right(oc))
      }
    }

  /**
   * Races the evaluation of two fibers that returns the result of the winner,
   * except in the case of cancellation.
   *
   * The semantics of [[race]] are described by the following rules:
   *
   *   1. If the winner completes with [[Succeeded]], the race returns the
   *   successful value. The loser is cancelled before returning.
   *   2. If the winner completes with [[Errored]], the race raises the error.
   *   The loser is cancelled before returning.
   *   3. If the winner completes with [[Canceled]], the race returns the
   *   result of the loser, consistent with the first two rules.
   *   4. If both the winner and loser complete with [[Cancelled]], the race
   *   is cancelled.
   *
   * @param fa the effect for the first racing fiber
   * @param fb the effect for the second racing fiber
   *
   * @see [[raceOutcome]] for a variant that returns the outcome of the winner.
   */
  def race[A, B](fa: F[A], fb: F[B]): F[Either[A, B]] =
    uncancelable { poll =>
      flatMap(racePair(fa, fb)) {
        case Left((oc, f)) =>
          oc match {
            case Outcome.Completed(fa) => productR(f.cancel)(map(fa)(Left(_)))
            case Outcome.Errored(ea) => productR(f.cancel)(raiseError(ea))
            case Outcome.Canceled() =>
              flatMap(onCancel(poll(f.join), f.cancel)) {
                case Outcome.Completed(fb) => map(fb)(Right(_))
                case Outcome.Errored(eb) => raiseError(eb)
                case Outcome.Canceled() => productR(canceled)(never)
              }
          }
        case Right((f, oc)) =>
          oc match {
            case Outcome.Completed(fb) => productR(f.cancel)(map(fb)(Right(_)))
            case Outcome.Errored(eb) => productR(f.cancel)(raiseError(eb))
            case Outcome.Canceled() =>
              flatMap(onCancel(poll(f.join), f.cancel)) {
                case Outcome.Completed(fa) => map(fa)(Left(_))
                case Outcome.Errored(ea) => raiseError(ea)
                case Outcome.Canceled() => productR(canceled)(never)
              }
          }
      }
    }

  /**
   * Races the evaluation of two fibers and returns the [[Outcome]] of both. If
   * the race is cancelled before one or both participants complete, then
   * then the incomplete ones are cancelled.
   *
   * @param fa the effect for the first racing fiber
   * @param fb the effect for the second racing fiber
   *
   * @see [[both]] for a simpler variant that returns the results of both
   * fibers.
   */
  def bothOutcome[A, B](fa: F[A], fb: F[B]): F[(Outcome[F, E, A], Outcome[F, E, B])] =
    uncancelable { poll =>
      flatMap(racePair(fa, fb)) {
        case Left((oc, f)) => map(onCancel(poll(f.join), f.cancel))((oc, _))
        case Right((f, oc)) => map(onCancel(poll(f.join), f.cancel))((_, oc))
      }
    }

  /**
   * Races the evaluation of two fibers and returns the result of both.
   *
   * The following rules describe the semantics of [[both]]:
   *
   *   1. If the winner completes with [[Succeeded]], the race waits for the
   *   loser to complete.
   *   2. If the winner completes with [[Errored]], the race raises the error.
   *   The loser is cancelled.
   *   3. If the winner completes with [[Canceled]], the loser and the race
   *   are cancelled as well.
   *   4. If the loser completes with [[Succeeded]], the race returns the
   *   successful value of both fibers.
   *   5. If the loser completes with [[Errored]], the race returns the error.
   *   6. If the loser completes with [[Canceled]], the race is cancelled.
   *   7. If the race is cancelled before one or both participants complete,
   *   then the incomplete ones are cancelled.
   *
   * @param fa the effect for the first racing fiber
   * @param fb the effect for the second racing fiber
   *
   * @see [[bothOutcome]] for a variant that returns the [[Outcome]] of both
   * fibers.
   */
  def both[A, B](fa: F[A], fb: F[B]): F[(A, B)] =
    uncancelable { poll =>
      flatMap(racePair(fa, fb)) {
        case Left((oc, f)) =>
          oc match {
            case Outcome.Completed(fa) =>
              flatMap(onCancel(poll(f.join), f.cancel)) {
                case Outcome.Completed(fb) => product(fa, fb)
                case Outcome.Errored(eb) => raiseError(eb)
                case Outcome.Canceled() => productR(canceled)(never)
              }
            case Outcome.Errored(ea) => productR(f.cancel)(raiseError(ea))
            case Outcome.Canceled() => productR(f.cancel)(productR(canceled)(never))
          }
        case Right((f, oc)) =>
          oc match {
            case Outcome.Completed(fb) =>
              flatMap(onCancel(poll(f.join), f.cancel)) {
                case Outcome.Completed(fa) => product(fa, fb)
                case Outcome.Errored(ea) => raiseError(ea)
                case Outcome.Canceled() => productR(canceled)(never)
              }
            case Outcome.Errored(eb) => productR(f.cancel)(raiseError(eb))
            case Outcome.Canceled() => productR(f.cancel)(productR(canceled)(never))
          }
      }
    }
}

object GenSpawn {
  import MonadCancel.{
    EitherTMonadCancel,
    IorTMonadCancel,
    KleisliMonadCancel,
    OptionTMonadCancel,
    WriterTMonadCancel
  }

  def apply[F[_], E](implicit F: GenSpawn[F, E]): F.type = F
  def apply[F[_]](implicit F: GenSpawn[F, _], d: DummyImplicit): F.type = F

  implicit def genSpawnForOptionT[F[_], E](
      implicit F0: GenSpawn[F, E]): GenSpawn[OptionT[F, *], E] =
    new OptionTGenSpawn[F, E] {

      override implicit protected def F: GenSpawn[F, E] = F0
    }

  implicit def genSpawnForEitherT[F[_], E0, E](
      implicit F0: GenSpawn[F, E]): GenSpawn[EitherT[F, E0, *], E] =
    new EitherTGenSpawn[F, E0, E] {

      override implicit protected def F: GenSpawn[F, E] = F0
    }

  implicit def genSpawnForKleisli[F[_], R, E](
      implicit F0: GenSpawn[F, E]): GenSpawn[Kleisli[F, R, *], E] =
    new KleisliGenSpawn[F, R, E] {

      override implicit protected def F: GenSpawn[F, E] = F0
    }

  implicit def genSpawnForIorT[F[_], L, E](
      implicit F0: GenSpawn[F, E],
      L0: Semigroup[L]): GenSpawn[IorT[F, L, *], E] =
    new IorTGenSpawn[F, L, E] {

      override implicit protected def F: GenSpawn[F, E] = F0

      override implicit protected def L: Semigroup[L] = L0
    }

  implicit def genSpawnForWriterT[F[_], L, E](
      implicit F0: GenSpawn[F, E],
      L0: Monoid[L]): GenSpawn[WriterT[F, L, *], E] =
    new WriterTGenSpawn[F, L, E] {

      override implicit protected def F: GenSpawn[F, E] = F0

      override implicit protected def L: Monoid[L] = L0
    }

  private[kernel] trait OptionTGenSpawn[F[_], E]
      extends GenSpawn[OptionT[F, *], E]
      with OptionTMonadCancel[F, E] {

    implicit protected def F: GenSpawn[F, E]

    def start[A](fa: OptionT[F, A]): OptionT[F, Fiber[OptionT[F, *], E, A]] =
      OptionT.liftF(F.start(fa.value).map(liftFiber))

    def never[A]: OptionT[F, A] = OptionT.liftF(F.never)

    def cede: OptionT[F, Unit] = OptionT.liftF(F.cede)

    def racePair[A, B](fa: OptionT[F, A], fb: OptionT[F, B]): OptionT[
      F,
      Either[
        (Outcome[OptionT[F, *], E, A], Fiber[OptionT[F, *], E, B]),
        (Fiber[OptionT[F, *], E, A], Outcome[OptionT[F, *], E, B])]] = {
      OptionT.liftF(F.racePair(fa.value, fb.value).map {
        case Left((oc, fib)) => Left((liftOutcome(oc), liftFiber(fib)))
        case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
      })
    }

    def liftOutcome[A](oc: Outcome[F, E, Option[A]]): Outcome[OptionT[F, *], E, A] =
      oc match {
        case Outcome.Canceled() => Outcome.Canceled()
        case Outcome.Errored(e) => Outcome.Errored(e)
        case Outcome.Completed(foa) => Outcome.Completed(OptionT(foa))
      }

    def liftFiber[A](fib: Fiber[F, E, Option[A]]): Fiber[OptionT[F, *], E, A] =
      new Fiber[OptionT[F, *], E, A] {
        def cancel: OptionT[F, Unit] = OptionT.liftF(fib.cancel)
        def join: OptionT[F, Outcome[OptionT[F, *], E, A]] =
          OptionT.liftF(fib.join.map(liftOutcome))
      }
  }

  private[kernel] trait EitherTGenSpawn[F[_], E0, E]
      extends GenSpawn[EitherT[F, E0, *], E]
      with EitherTMonadCancel[F, E0, E] {

    implicit protected def F: GenSpawn[F, E]

    def start[A](fa: EitherT[F, E0, A]): EitherT[F, E0, Fiber[EitherT[F, E0, *], E, A]] =
      EitherT.liftF(F.start(fa.value).map(liftFiber))

    def never[A]: EitherT[F, E0, A] = EitherT.liftF(F.never)

    def cede: EitherT[F, E0, Unit] = EitherT.liftF(F.cede)

    def racePair[A, B](fa: EitherT[F, E0, A], fb: EitherT[F, E0, B]): EitherT[
      F,
      E0,
      Either[
        (Outcome[EitherT[F, E0, *], E, A], Fiber[EitherT[F, E0, *], E, B]),
        (Fiber[EitherT[F, E0, *], E, A], Outcome[EitherT[F, E0, *], E, B])]] = {
      EitherT.liftF(F.racePair(fa.value, fb.value).map {
        case Left((oc, fib)) => Left((liftOutcome(oc), liftFiber(fib)))
        case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
      })
    }

    def liftOutcome[A](oc: Outcome[F, E, Either[E0, A]]): Outcome[EitherT[F, E0, *], E, A] =
      oc match {
        case Outcome.Canceled() => Outcome.Canceled()
        case Outcome.Errored(e) => Outcome.Errored(e)
        case Outcome.Completed(foa) => Outcome.Completed(EitherT(foa))
      }

    def liftFiber[A](fib: Fiber[F, E, Either[E0, A]]): Fiber[EitherT[F, E0, *], E, A] =
      new Fiber[EitherT[F, E0, *], E, A] {
        def cancel: EitherT[F, E0, Unit] = EitherT.liftF(fib.cancel)
        def join: EitherT[F, E0, Outcome[EitherT[F, E0, *], E, A]] =
          EitherT.liftF(fib.join.map(liftOutcome))
      }
  }

  private[kernel] trait IorTGenSpawn[F[_], L, E]
      extends GenSpawn[IorT[F, L, *], E]
      with IorTMonadCancel[F, L, E] {

    implicit protected def F: GenSpawn[F, E]

    implicit protected def L: Semigroup[L]

    def start[A](fa: IorT[F, L, A]): IorT[F, L, Fiber[IorT[F, L, *], E, A]] =
      IorT.liftF(F.start(fa.value).map(liftFiber))

    def never[A]: IorT[F, L, A] = IorT.liftF(F.never)

    def cede: IorT[F, L, Unit] = IorT.liftF(F.cede)

    def racePair[A, B](fa: IorT[F, L, A], fb: IorT[F, L, B]): IorT[
      F,
      L,
      Either[
        (Outcome[IorT[F, L, *], E, A], Fiber[IorT[F, L, *], E, B]),
        (Fiber[IorT[F, L, *], E, A], Outcome[IorT[F, L, *], E, B])]] = {
      IorT.liftF(F.racePair(fa.value, fb.value).map {
        case Left((oc, fib)) => Left((liftOutcome(oc), liftFiber(fib)))
        case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
      })
    }

    def liftOutcome[A](oc: Outcome[F, E, Ior[L, A]]): Outcome[IorT[F, L, *], E, A] =
      oc match {
        case Outcome.Canceled() => Outcome.Canceled()
        case Outcome.Errored(e) => Outcome.Errored(e)
        case Outcome.Completed(foa) => Outcome.Completed(IorT(foa))
      }

    def liftFiber[A](fib: Fiber[F, E, Ior[L, A]]): Fiber[IorT[F, L, *], E, A] =
      new Fiber[IorT[F, L, *], E, A] {
        def cancel: IorT[F, L, Unit] = IorT.liftF(fib.cancel)
        def join: IorT[F, L, Outcome[IorT[F, L, *], E, A]] =
          IorT.liftF(fib.join.map(liftOutcome))
      }
  }

  private[kernel] trait KleisliGenSpawn[F[_], R, E]
      extends GenSpawn[Kleisli[F, R, *], E]
      with KleisliMonadCancel[F, R, E] {

    implicit protected def F: GenSpawn[F, E]

    def start[A](fa: Kleisli[F, R, A]): Kleisli[F, R, Fiber[Kleisli[F, R, *], E, A]] =
      Kleisli { r => (F.start(fa.run(r)).map(liftFiber)) }

    def never[A]: Kleisli[F, R, A] = Kleisli.liftF(F.never)

    def cede: Kleisli[F, R, Unit] = Kleisli.liftF(F.cede)

    def racePair[A, B](fa: Kleisli[F, R, A], fb: Kleisli[F, R, B]): Kleisli[
      F,
      R,
      Either[
        (Outcome[Kleisli[F, R, *], E, A], Fiber[Kleisli[F, R, *], E, B]),
        (Fiber[Kleisli[F, R, *], E, A], Outcome[Kleisli[F, R, *], E, B])]] = {
      Kleisli { r =>
        (F.racePair(fa.run(r), fb.run(r)).map {
          case Left((oc, fib)) => Left((liftOutcome(oc), liftFiber(fib)))
          case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
        })
      }
    }

    def liftOutcome[A](oc: Outcome[F, E, A]): Outcome[Kleisli[F, R, *], E, A] = {

      val nat: F ~> Kleisli[F, R, *] = new ~>[F, Kleisli[F, R, *]] {
        def apply[B](fa: F[B]) = Kleisli.liftF(fa)
      }

      oc.mapK(nat)
    }

    def liftFiber[A](fib: Fiber[F, E, A]): Fiber[Kleisli[F, R, *], E, A] =
      new Fiber[Kleisli[F, R, *], E, A] {
        def cancel: Kleisli[F, R, Unit] = Kleisli.liftF(fib.cancel)
        def join: Kleisli[F, R, Outcome[Kleisli[F, R, *], E, A]] =
          Kleisli.liftF(fib.join.map(liftOutcome))
      }
  }

  private[kernel] trait WriterTGenSpawn[F[_], L, E]
      extends GenSpawn[WriterT[F, L, *], E]
      with WriterTMonadCancel[F, L, E] {

    implicit protected def F: GenSpawn[F, E]

    implicit protected def L: Monoid[L]

    def start[A](fa: WriterT[F, L, A]): WriterT[F, L, Fiber[WriterT[F, L, *], E, A]] =
      WriterT.liftF(F.start(fa.run).map(liftFiber))

    def never[A]: WriterT[F, L, A] = WriterT.liftF(F.never)

    def cede: WriterT[F, L, Unit] = WriterT.liftF(F.cede)

    def racePair[A, B](fa: WriterT[F, L, A], fb: WriterT[F, L, B]): WriterT[
      F,
      L,
      Either[
        (Outcome[WriterT[F, L, *], E, A], Fiber[WriterT[F, L, *], E, B]),
        (Fiber[WriterT[F, L, *], E, A], Outcome[WriterT[F, L, *], E, B])]] = {
      WriterT.liftF(F.racePair(fa.run, fb.run).map {
        case Left((oc, fib)) => Left((liftOutcome(oc), liftFiber(fib)))
        case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
      })
    }

    def liftOutcome[A](oc: Outcome[F, E, (L, A)]): Outcome[WriterT[F, L, *], E, A] =
      oc match {
        case Outcome.Canceled() => Outcome.Canceled()
        case Outcome.Errored(e) => Outcome.Errored(e)
        case Outcome.Completed(foa) => Outcome.Completed(WriterT(foa))
      }

    def liftFiber[A](fib: Fiber[F, E, (L, A)]): Fiber[WriterT[F, L, *], E, A] =
      new Fiber[WriterT[F, L, *], E, A] {
        def cancel: WriterT[F, L, Unit] = WriterT.liftF(fib.cancel)
        def join: WriterT[F, L, Outcome[WriterT[F, L, *], E, A]] =
          WriterT.liftF(fib.join.map(liftOutcome))
      }
  }
}
