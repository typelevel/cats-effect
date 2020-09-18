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

import cats.{Monoid, Semigroup}
import cats.syntax.all._
import cats.effect.kernel.syntax.all._
import cats.data.{EitherT, IorT, Kleisli, OptionT, WriterT}

trait GenConcurrent[F[_], E] extends GenSpawn[F, E] {

  def ref[A](a: A): F[Ref[F, A]]

  def deferred[A]: F[Deferred[F, A]]

  def memoize[A](fa: F[A]): F[F[A]] =
    GenConcurrent.memoize(fa)(this)

}

object GenConcurrent {
  def apply[F[_], E](implicit F: GenConcurrent[F, E]): F.type = F
  def apply[F[_]](implicit F: GenConcurrent[F, _], d: DummyImplicit): F.type = F

  def memoize[F[_], E, A](fa: F[A])(implicit F: GenConcurrent[F, E]): F[F[A]] = {
    import Memoize._

    F.ref[Memoize[F, E, A]](Start()).map { state =>
      F.deferred[Either[E, A]].product(F.deferred[F[Unit]]).flatMap {
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
            F.uncancelable { poll =>
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

          F.uncancelable { poll =>
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
  }

  private[kernel] trait EitherTGenConcurrent[F[_], E0, E]
      extends GenConcurrent[EitherT[F, E0, *], E]
      with GenSpawn.EitherTGenSpawn[F, E0, E] {
    implicit protected def F: GenConcurrent[F, E]

    override def ref[A](a: A): EitherT[F, E0, Ref[EitherT[F, E0, *], A]] =
      EitherT.liftF(F.map(F.ref(a))(_.mapK(EitherT.liftK)))

    override def deferred[A]: EitherT[F, E0, Deferred[EitherT[F, E0, *], A]] =
      EitherT.liftF(F.map(F.deferred[A])(_.mapK(EitherT.liftK)))
  }

  private[kernel] trait KleisliGenConcurrent[F[_], R, E]
      extends GenConcurrent[Kleisli[F, R, *], E]
      with GenSpawn.KleisliGenSpawn[F, R, E] {
    implicit protected def F: GenConcurrent[F, E]

    override def ref[A](a: A): Kleisli[F, R, Ref[Kleisli[F, R, *], A]] =
      Kleisli.liftF(F.map(F.ref(a))(_.mapK(Kleisli.liftK)))

    override def deferred[A]: Kleisli[F, R, Deferred[Kleisli[F, R, *], A]] =
      Kleisli.liftF(F.map(F.deferred[A])(_.mapK(Kleisli.liftK)))
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
  }

}
