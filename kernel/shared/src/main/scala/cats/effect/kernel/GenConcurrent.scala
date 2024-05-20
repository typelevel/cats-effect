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

package cats.effect.kernel

import cats.{Foldable, Monoid, Semigroup, Traverse}
import cats.data.{EitherT, IorT, Kleisli, OptionT, WriterT}
import cats.effect.kernel.instances.spawn._
import cats.effect.kernel.syntax.all._
import cats.syntax.all._

trait GenConcurrent[F[_], E] extends GenSpawn[F, E] {

  import GenConcurrent._

  def ref[A](a: A): F[Ref[F, A]]

  def deferred[A]: F[Deferred[F, A]]

  /**
   * Caches the result of `fa`.
   *
   * The returned inner effect, hence referred to as `get`, when sequenced, will evaluate `fa`
   * and cache the result. If `get` is sequenced multiple times `fa` will only be evaluated
   * once.
   *
   * If all `get`s are canceled prior to `fa` completing, it will be canceled and evaluated
   * again the next time `get` is sequenced.
   */
  def memoize[A](fa: F[A]): F[F[A]] = {
    import Memoize._
    implicit val F: GenConcurrent[F, E] = this

    ref[Memoize[F, E, A]](Unevaluated()) map { state =>
      // start running the effect, or subscribe if it already is
      def evalOrSubscribe: F[A] =
        deferred[Fiber[F, E, A]] flatMap { deferredFiber =>
          state.flatModifyFull {
            case (poll, Unevaluated()) =>
              // run the effect, and if this fiber is still relevant set its result
              val go = {
                def tryComplete(result: Memoize[F, E, A]): F[Unit] = state.update {
                  case Evaluating(fiber, _) if fiber eq deferredFiber =>
                    // we are the blessed fiber of this memo
                    result
                  case other => // our outcome is no longer relevant
                    other
                }

                fa
                  // hack around functor law breakage
                  .flatMap(F.pure(_))
                  .handleErrorWith(F.raiseError(_))
                  // end hack
                  .guaranteeCase {
                    case Outcome.Canceled() =>
                      tryComplete(Finished(Right(productR(canceled)(never))))
                    case Outcome.Errored(err) =>
                      tryComplete(Finished(Left(err)))
                    case Outcome.Succeeded(fa) =>
                      tryComplete(Finished(Right(fa)))
                  }
              }

              val eval = go.start.flatMap { fiber =>
                deferredFiber.complete(fiber) *>
                  poll(fiber.join.flatMap(_.embed(productR(canceled)(never))))
                    .onCancel(unsubscribe(deferredFiber))
              }

              Evaluating(deferredFiber, 1) -> eval

            case (poll, Evaluating(fiber, subscribers)) =>
              Evaluating(fiber, subscribers + 1) ->
                poll(fiber.get.flatMap(_.join).flatMap(_.embed(productR(canceled)(never))))
                  .onCancel(unsubscribe(fiber))

            case (_, finished @ Finished(result)) =>
              finished -> fromEither(result).flatten
          }
        }

      def unsubscribe(expected: Deferred[F, Fiber[F, E, A]]): F[Unit] =
        state.modify {
          case Evaluating(fiber, subscribers) if fiber eq expected =>
            if (subscribers == 1) // we are the last subscriber to this fiber
              Unevaluated() -> fiber.get.flatMap(_.cancel)
            else
              Evaluating(fiber, subscribers - 1) -> unit
          case other =>
            other -> unit
        }.flatten

      state.get.flatMap {
        case Finished(result) => fromEither(result).flatten
        case _ => evalOrSubscribe
      }
    }
  }

  /**
   * Like `Parallel.parReplicateA`, but limits the degree of parallelism.
   */
  def parReplicateAN[A](n: Int)(replicas: Int, ma: F[A]): F[List[A]] =
    parSequenceN(n)(List.fill(replicas)(ma))

  /**
   * Like `Parallel.parSequence`, but limits the degree of parallelism.
   */
  def parSequenceN[T[_]: Traverse, A](n: Int)(tma: T[F[A]]): F[T[A]] =
    parTraverseN(n)(tma)(identity)

  /**
   * Like `Parallel.parSequence_`, but limits the degree of parallelism.
   */
  def parSequenceN_[T[_]: Foldable, A](n: Int)(tma: T[F[A]]): F[Unit] =
    parTraverseN_(n)(tma)(identity)

  /**
   * Like `Parallel.parTraverse`, but limits the degree of parallelism. Note that the semantics
   * of this operation aim to maximise fairness: when a spot to execute becomes available, every
   * task has a chance to claim it, and not only the next `n` tasks in `ta`
   */
  def parTraverseN[T[_]: Traverse, A, B](n: Int)(ta: T[A])(f: A => F[B]): F[T[B]] = {
    require(n >= 1, s"Concurrency limit should be at least 1, was: $n")

    implicit val F: GenConcurrent[F, E] = this

    MiniSemaphore[F](n).flatMap { sem => ta.parTraverse { a => sem.withPermit(f(a)) } }
  }

  /**
   * Like `Parallel.parTraverse_`, but limits the degree of parallelism. Note that the semantics
   * of this operation aim to maximise fairness: when a spot to execute becomes available, every
   * task has a chance to claim it, and not only the next `n` tasks in `ta`
   */
  def parTraverseN_[T[_]: Foldable, A, B](n: Int)(ta: T[A])(f: A => F[B]): F[Unit] = {
    require(n >= 1, s"Concurrency limit should be at least 1, was: $n")

    implicit val F: GenConcurrent[F, E] = this

    MiniSemaphore[F](n).flatMap { sem => ta.parTraverse_ { a => sem.withPermit(f(a)) } }
  }

  override def racePair[A, B](fa: F[A], fb: F[B])
      : F[Either[(Outcome[F, E, A], Fiber[F, E, B]), (Fiber[F, E, A], Outcome[F, E, B])]] = {
    implicit val F: GenConcurrent[F, E] = this

    uncancelable { poll =>
      for {
        result <-
          deferred[Either[Outcome[F, E, A], Outcome[F, E, B]]]

        fibA <- start(guaranteeCase(fa)(oc => result.complete(Left(oc)).void))
        fibB <- start(guaranteeCase(fb)(oc => result.complete(Right(oc)).void))

        back <- onCancel(
          poll(result.get),
          for {
            canA <- start(fibA.cancel)
            canB <- start(fibB.cancel)

            _ <- canA.join
            _ <- canB.join
          } yield ())
      } yield back match {
        case Left(oc) => Left((oc, fibB))
        case Right(oc) => Right((fibA, oc))
      }
    }
  }
}

object GenConcurrent {
  def apply[F[_], E](implicit F: GenConcurrent[F, E]): F.type = F
  def apply[F[_]](implicit F: GenConcurrent[F, _], d: DummyImplicit): F.type = F

  private sealed abstract class Memoize[F[_], E, A]
  private object Memoize {
    final case class Unevaluated[F[_], E, A]() extends Memoize[F, E, A]
    final case class Evaluating[F[_], E, A](
        fiber: Deferred[F, Fiber[F, E, A]],
        subscribers: Long
    ) extends Memoize[F, E, A]
    final case class Finished[F[_], E, A](result: Either[E, F[A]]) extends Memoize[F, E, A]
  }

  implicit def genConcurrentForOptionT[F[_], E](
      implicit F0: GenConcurrent[F, E]): GenConcurrent[OptionT[F, *], E] =
    F0 match {
      case async: Async[F @unchecked] =>
        Async.asyncForOptionT[F](async)
      case temporal: GenTemporal[F @unchecked, E @unchecked] =>
        GenTemporal.instantiateGenTemporalForOptionT[F, E](temporal)
      case concurrent =>
        instantiateGenConcurrentForOptionT(concurrent)
    }

  private[kernel] def instantiateGenConcurrentForOptionT[F[_], E](
      F0: GenConcurrent[F, E]): OptionTGenConcurrent[F, E] =
    new OptionTGenConcurrent[F, E] {
      override implicit protected def F: GenConcurrent[F, E] = F0
    }

  implicit def genConcurrentForEitherT[F[_], E0, E](
      implicit F0: GenConcurrent[F, E]): GenConcurrent[EitherT[F, E0, *], E] =
    F0 match {
      case async: Async[F @unchecked] =>
        Async.asyncForEitherT[F, E0](async)
      case temporal: GenTemporal[F @unchecked, E @unchecked] =>
        GenTemporal.instantiateGenTemporalForEitherT[F, E0, E](temporal)
      case concurrent =>
        instantiateGenConcurrentForEitherT(concurrent)
    }

  private[kernel] def instantiateGenConcurrentForEitherT[F[_], E0, E](
      F0: GenConcurrent[F, E]): EitherTGenConcurrent[F, E0, E] =
    new EitherTGenConcurrent[F, E0, E] {
      override implicit protected def F: GenConcurrent[F, E] = F0
    }

  implicit def genConcurrentForKleisli[F[_], R, E](
      implicit F0: GenConcurrent[F, E]): GenConcurrent[Kleisli[F, R, *], E] =
    F0 match {
      case async: Async[F @unchecked] =>
        Async.asyncForKleisli[F, R](async)
      case temporal: GenTemporal[F @unchecked, E @unchecked] =>
        GenTemporal.instantiateGenTemporalForKleisli[F, R, E](temporal)
      case concurrent =>
        instantiateGenConcurrentForKleisli(concurrent)
    }

  private[kernel] def instantiateGenConcurrentForKleisli[F[_], R, E](
      F0: GenConcurrent[F, E]): KleisliGenConcurrent[F, R, E] =
    new KleisliGenConcurrent[F, R, E] {
      override implicit protected def F: GenConcurrent[F, E] = F0
    }

  implicit def genConcurrentForIorT[F[_], L, E](
      implicit F0: GenConcurrent[F, E],
      L0: Semigroup[L]): GenConcurrent[IorT[F, L, *], E] =
    F0 match {
      case async: Async[F @unchecked] =>
        Async.asyncForIorT[F, L](async, L0)
      case temporal: GenTemporal[F @unchecked, E @unchecked] =>
        GenTemporal.instantiateGenTemporalForIorT[F, L, E](temporal)
      case concurrent =>
        instantiateGenConcurrentForIorT(concurrent)
    }

  private[kernel] def instantiateGenConcurrentForIorT[F[_], L, E](F0: GenConcurrent[F, E])(
      implicit L0: Semigroup[L]): IorTGenConcurrent[F, L, E] =
    new IorTGenConcurrent[F, L, E] {
      override implicit protected def F: GenConcurrent[F, E] = F0
      override implicit protected def L: Semigroup[L] = L0
    }

  implicit def genConcurrentForWriterT[F[_], L, E](
      implicit F0: GenConcurrent[F, E],
      L0: Monoid[L]): GenConcurrent[WriterT[F, L, *], E] =
    F0 match {
      case async: Async[F @unchecked] =>
        Async.asyncForWriterT[F, L](async, L0)
      case temporal: GenTemporal[F @unchecked, E @unchecked] =>
        GenTemporal.instantiateGenTemporalForWriterT[F, L, E](temporal)
      case concurrent =>
        instantiateGenConcurrentForWriterT(concurrent)
    }

  private[kernel] def instantiateGenConcurrentForWriterT[F[_], L, E](F0: GenConcurrent[F, E])(
      implicit L0: Monoid[L]): WriterTGenConcurrent[F, L, E] =
    new WriterTGenConcurrent[F, L, E] {
      override implicit protected def F: GenConcurrent[F, E] = F0
      override implicit protected def L: Monoid[L] = L0
    }

  private[kernel] trait OptionTGenConcurrent[F[_], E]
      extends GenConcurrent[OptionT[F, *], E]
      with GenSpawn.OptionTGenSpawn[F, E] {
    implicit protected def F: GenConcurrent[F, E]

    override def ref[A](a: A): OptionT[F, Ref[OptionT[F, *], A]] =
      OptionT.liftF(F.map(F.ref(a))(_.mapK(OptionT.liftK)))

    override def deferred[A]: OptionT[F, Deferred[OptionT[F, *], A]] =
      OptionT.liftF(F.map(F.deferred[A])(_.mapK(OptionT.liftK)))

    override def racePair[A, B](fa: OptionT[F, A], fb: OptionT[F, B]): OptionT[
      F,
      Either[
        (Outcome[OptionT[F, *], E, A], Fiber[OptionT[F, *], E, B]),
        (Fiber[OptionT[F, *], E, A], Outcome[OptionT[F, *], E, B])]] =
      super.racePair(fa, fb)
  }

  private[kernel] trait EitherTGenConcurrent[F[_], E0, E]
      extends GenConcurrent[EitherT[F, E0, *], E]
      with GenSpawn.EitherTGenSpawn[F, E0, E] {
    implicit protected def F: GenConcurrent[F, E]

    override def ref[A](a: A): EitherT[F, E0, Ref[EitherT[F, E0, *], A]] =
      EitherT.liftF(F.map(F.ref(a))(_.mapK(EitherT.liftK)))

    override def deferred[A]: EitherT[F, E0, Deferred[EitherT[F, E0, *], A]] =
      EitherT.liftF(F.map(F.deferred[A])(_.mapK(EitherT.liftK)))

    override def racePair[A, B](fa: EitherT[F, E0, A], fb: EitherT[F, E0, B]): EitherT[
      F,
      E0,
      Either[
        (Outcome[EitherT[F, E0, *], E, A], Fiber[EitherT[F, E0, *], E, B]),
        (Fiber[EitherT[F, E0, *], E, A], Outcome[EitherT[F, E0, *], E, B])]] =
      super.racePair(fa, fb)
  }

  private[kernel] trait KleisliGenConcurrent[F[_], R, E]
      extends GenConcurrent[Kleisli[F, R, *], E]
      with GenSpawn.KleisliGenSpawn[F, R, E] {
    implicit protected def F: GenConcurrent[F, E]

    override def ref[A](a: A): Kleisli[F, R, Ref[Kleisli[F, R, *], A]] =
      Kleisli.liftF(F.map(F.ref(a))(_.mapK(Kleisli.liftK)))

    override def deferred[A]: Kleisli[F, R, Deferred[Kleisli[F, R, *], A]] =
      Kleisli.liftF(F.map(F.deferred[A])(_.mapK(Kleisli.liftK)))

    override def racePair[A, B](fa: Kleisli[F, R, A], fb: Kleisli[F, R, B]): Kleisli[
      F,
      R,
      Either[
        (Outcome[Kleisli[F, R, *], E, A], Fiber[Kleisli[F, R, *], E, B]),
        (Fiber[Kleisli[F, R, *], E, A], Outcome[Kleisli[F, R, *], E, B])]] =
      super.racePair(fa, fb)
  }

  private[kernel] trait IorTGenConcurrent[F[_], L, E]
      extends GenConcurrent[IorT[F, L, *], E]
      with GenSpawn.IorTGenSpawn[F, L, E] {
    implicit protected def F: GenConcurrent[F, E]

    implicit protected def L: Semigroup[L]

    override def ref[A](a: A): IorT[F, L, Ref[IorT[F, L, *], A]] =
      IorT.liftF(F.map(F.ref(a))(_.mapK(IorT.liftK)))

    override def deferred[A]: IorT[F, L, Deferred[IorT[F, L, *], A]] =
      IorT.liftF(F.map(F.deferred[A])(_.mapK(IorT.liftK)))

    override def racePair[A, B](fa: IorT[F, L, A], fb: IorT[F, L, B]): IorT[
      F,
      L,
      Either[
        (Outcome[IorT[F, L, *], E, A], Fiber[IorT[F, L, *], E, B]),
        (Fiber[IorT[F, L, *], E, A], Outcome[IorT[F, L, *], E, B])]] =
      super.racePair(fa, fb)
  }

  private[kernel] trait WriterTGenConcurrent[F[_], L, E]
      extends GenConcurrent[WriterT[F, L, *], E]
      with GenSpawn.WriterTGenSpawn[F, L, E] {

    implicit protected def F: GenConcurrent[F, E]

    implicit protected def L: Monoid[L]

    override def ref[A](a: A): WriterT[F, L, Ref[WriterT[F, L, *], A]] =
      WriterT.liftF(F.map(F.ref(a))(_.mapK(WriterT.liftK)))

    override def deferred[A]: WriterT[F, L, Deferred[WriterT[F, L, *], A]] =
      WriterT.liftF(F.map(F.deferred[A])(_.mapK(WriterT.liftK)))

    override def racePair[A, B](fa: WriterT[F, L, A], fb: WriterT[F, L, B]): WriterT[
      F,
      L,
      Either[
        (Outcome[WriterT[F, L, *], E, A], Fiber[WriterT[F, L, *], E, B]),
        (Fiber[WriterT[F, L, *], E, A], Outcome[WriterT[F, L, *], E, B])]] =
      super.racePair(fa, fb)
  }

}
