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

import cats.{Monoid, Parallel, Semigroup, Traverse}
import cats.syntax.all._
import cats.effect.kernel.syntax.all._
import cats.data.{EitherT, IorT, Kleisli, OptionT, WriterT}

trait GenConcurrent[F[_], E] extends GenSpawn[F, E] {

  import GenConcurrent._

  def ref[A](a: A): F[Ref[F, A]]

  def deferred[A]: F[Deferred[F, A]]

  def memoize[A](fa: F[A]): F[F[A]] = {
    import Memoize._
    implicit val F: GenConcurrent[F, E] = this

    ref[Memoize[F, E, A]](Start()).map { state =>
      deferred[Either[E, A]].product(F.deferred[F[Unit]]).flatMap {
        case (value, stop) =>
          def removeSubscriber: F[Unit] =
            state.modify {
              case Start() =>
                throw new AssertionError("unreachable")
              case Running(subs, value, stop) =>
                if (subs > 1) {
                  Running(subs - 1, value, stop) -> F.unit
                } else {
                  Start() -> stop.get.flatten
                }
              case st @ Done(_) =>
                st -> F.unit
            }.flatten

          def fetch: F[Either[E, A]] =
            uncancelable { poll =>
              for {
                result0 <- poll(fa).attempt
                // in some interleavings, there may be several racing fetches.
                // always respect the first completion.
                result <- state.modify {
                  case st @ Done(result) => st -> result
                  case _ => Done(result0) -> result0
                }
                _ <- value.complete(result)
              } yield result
            }

          uncancelable { poll =>
            state.modify {
              case Start() => {
                val start = fetch.start.flatMap(fiber => stop.complete(fiber.cancel))
                Running(1, value, stop) -> start *> poll(value.get).onCancel(removeSubscriber)
              }
              case Running(subs, value, stop) =>
                Running(subs + 1, value, stop) -> poll(value.get).onCancel(removeSubscriber)
              case st @ Done(value) =>
                st -> F.pure(value)
            }.flatten
          }.rethrow
      }
    }
  }

  /**
   * Like `Parallel.parSequence`, but limits the degree of parallelism.
   */
  def parSequenceN[T[_]: Traverse, A](n: Int)(
      tma: T[F[A]])(implicit P: Parallel[F], ev: E <:< Throwable): F[T[A]] = {
    implicit val F: Concurrent[F] = this.asInstanceOf[GenConcurrent[F, Throwable]]
    for {
      semaphore <- Semaphore[F](n.toLong)
      mta <- tma.map(x => semaphore.permit.use(_ => x)).parSequence
    } yield mta
  }

  /**
   * Like `Parallel.parTraverse`, but limits the degree of parallelism.
   */
  def parTraverseN[T[_]: Traverse, A, B](n: Int)(ta: T[A])(
      f: A => F[B])(implicit P: Parallel[F], ev: E <:< Throwable): F[T[B]] = {
    implicit val F: Concurrent[F] = this.asInstanceOf[GenConcurrent[F, Throwable]]
    for {
      semaphore <- Semaphore[F](n.toLong)
      tb <- ta.parTraverse { a => semaphore.permit.use(_ => f(a)) }
    } yield tb
  }

  override def racePair[A, B](fa: F[A], fb: F[B])
      : F[Either[(Outcome[F, E, A], Fiber[F, E, B]), (Fiber[F, E, A], Outcome[F, E, B])]] = {
    implicit val F: GenConcurrent[F, E] = this

    uncancelable { poll =>
      for {
        fibADef <- deferred[Fiber[F, E, A]]
        fibBDef <- deferred[Fiber[F, E, B]]

        result <-
          deferred[
            Either[(Outcome[F, E, A], Fiber[F, E, B]), (Fiber[F, E, A], Outcome[F, E, B])]]

        fibA <- start {
          guaranteeCase(fa) { oc =>
            fibBDef.get flatMap { fibB => result.complete(Left((oc, fibB))).void }
          }
        }

        fibB <- start {
          guaranteeCase(fb) { oc =>
            fibADef.get flatMap { fibA => result.complete(Right((fibA, oc))).void }
          }
        }

        _ <- fibADef.complete(fibA)
        _ <- fibBDef.complete(fibB)

        back <- onCancel(
          poll(result.get), {
            for {
              done <- deferred[Unit]
              _ <- start(guarantee(fibA.cancel, done.complete(()).void))
              _ <- start(guarantee(fibB.cancel, done.complete(()).void))
              _ <- done.get
            } yield ()
          }
        )
      } yield back
    }
  }
}

object GenConcurrent {
  def apply[F[_], E](implicit F: GenConcurrent[F, E]): F.type = F
  def apply[F[_]](implicit F: GenConcurrent[F, _], d: DummyImplicit): F.type = F

  private sealed abstract class Memoize[F[_], E, A]
  private object Memoize {
    final case class Start[F[_], E, A]() extends Memoize[F, E, A]
    final case class Running[F[_], E, A](
        subs: Int,
        value: Deferred[F, Either[E, A]],
        stop: Deferred[F, F[Unit]])
        extends Memoize[F, E, A]
    final case class Done[F[_], E, A](value: Either[E, A]) extends Memoize[F, E, A]
  }

  implicit def genConcurrentForOptionT[F[_], E](
      implicit F0: GenConcurrent[F, E]): GenConcurrent[OptionT[F, *], E] =
    new OptionTGenConcurrent[F, E] {
      override implicit protected def F: GenConcurrent[F, E] = F0
    }

  implicit def genConcurrentForEitherT[F[_], E0, E](
      implicit F0: GenConcurrent[F, E]): GenConcurrent[EitherT[F, E0, *], E] =
    new EitherTGenConcurrent[F, E0, E] {
      override implicit protected def F: GenConcurrent[F, E] = F0
    }

  implicit def genConcurrentForKleisli[F[_], R, E](
      implicit F0: GenConcurrent[F, E]): GenConcurrent[Kleisli[F, R, *], E] =
    new KleisliGenConcurrent[F, R, E] {
      override implicit protected def F: GenConcurrent[F, E] = F0
    }

  implicit def genConcurrentForIorT[F[_], L, E](
      implicit F0: GenConcurrent[F, E],
      L0: Semigroup[L]): GenConcurrent[IorT[F, L, *], E] =
    new IorTGenConcurrent[F, L, E] {
      override implicit protected def F: GenConcurrent[F, E] = F0

      override implicit protected def L: Semigroup[L] = L0
    }

  implicit def genConcurrentForWriterT[F[_], L, E](
      implicit F0: GenConcurrent[F, E],
      L0: Monoid[L]): GenConcurrent[WriterT[F, L, *], E] =
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
