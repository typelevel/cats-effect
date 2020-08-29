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
import cats.data.{EitherT, IorT, Kleisli, OptionT, WriterT}

trait Allocate[F[_], E] extends Concurrent[F, E] {

  def ref[A](a: A): F[Ref[F, A]]

  def deferred[A](a: A): F[Deferred[F, A]]

}

object Allocate {
  def apply[F[_], E](implicit F: Allocate[F, E]): F.type = F
  def apply[F[_]](implicit F: Allocate[F, _], d: DummyImplicit): F.type = F

  implicit def allocateForOptionT[F[_], E](
                                            implicit F0: Allocate[F, E]): Allocate[OptionT[F, *], E] =
    new OptionTAllocate[F, E] {
      override implicit protected def F: Allocate[F, E] = F0
    }

  implicit def allocateForEitherT[F[_], E0, E](
                                                implicit F0: Allocate[F, E]): Allocate[EitherT[F, E0, *], E] =
    new EitherTAllocate[F, E0, E] {
      override implicit protected def F: Allocate[F, E] = F0
    }

  implicit def allocateForKleisli[F[_], R, E](
                                               implicit F0: Allocate[F, E]): Allocate[Kleisli[F, R, *], E] =
    new KleisliAllocate[F, R, E] {
      override implicit protected def F: Allocate[F, E] = F0
    }

  implicit def allocateForIorT[F[_], L, E](
                                            implicit F0: Allocate[F, E],
                                            L0: Semigroup[L]): Allocate[IorT[F, L, *], E] =
    new IorTAllocate[F, L, E] {
      override implicit protected def F: Allocate[F, E] = F0

      override implicit protected def L: Semigroup[L] = L0
    }

  implicit def allocateForWriterT[F[_], L, E](
                                               implicit F0: Allocate[F, E],
                                               L0: Monoid[L]): Allocate[WriterT[F, L, *], E] =
    new WriterTAllocate[F, L, E] {
      override implicit protected def F: Allocate[F, E] = F0

      override implicit protected def L: Monoid[L] = L0
    }
    
  private[kernel] trait OptionTAllocate[F[_], E] extends Allocate[OptionT[F, *], E] with Concurrent.OptionTConcurrent[F, E] {
    implicit protected def F: Allocate[F, E]

    override def ref[A](a: A): OptionT[F, Ref[OptionT[F, *], A]] = ???

    override def deferred[A](a: A): OptionT[F, Deferred[OptionT[F, *], A]] = ???
  }

  private[kernel] trait EitherTAllocate[F[_], E0, E] extends Allocate[EitherT[F, E0, *], E] with Concurrent.EitherTConcurrent[F, E0, E] {
    implicit protected def F: Allocate[F, E]

    override def ref[A](a: A): EitherT[F, E0, Ref[EitherT[F, E0, *], A]] = ???

    override def deferred[A](a: A): EitherT[F, E0, Deferred[EitherT[F, E0, *], A]] = ???
  }

  private[kernel] trait KleisliAllocate[F[_], R, E] extends Allocate[Kleisli[F, R, *], E] with Concurrent.KleisliConcurrent[F, R, E] {
    implicit protected def F: Allocate[F, E]

    override def ref[A](a: A): Kleisli[F, R, Ref[Kleisli[F, R, *], A]] = ???

    override def deferred[A](a: A): Kleisli[F, R, Deferred[Kleisli[F, R, *], A]] = ???
  }

  private[kernel] trait IorTAllocate[F[_], L, E] extends Allocate[IorT[F, L, *], E] with Concurrent.IorTConcurrent[F, L, E] {
    implicit protected def F: Allocate[F, E]

    implicit protected def L: Semigroup[L]

    override def ref[A](a: A): IorT[F, L, Ref[IorT[F, L, *], A]] = ???

    override def deferred[A](a: A): IorT[F, L, Deferred[IorT[F, L, *], A]] = ???
  }

  private[kernel] trait WriterTAllocate[F[_], L, E] extends Allocate[WriterT[F, L, *], E] with Concurrent.WriterTConcurrent[F, L, E] {

    protected def F: Allocate[F, E]

    protected def L: Monoid[L]

    override def ref[A](a: A): WriterT[F, L, Ref[WriterT[F, L, *], A]] = ???

    override def deferred[A](a: A): WriterT[F, L, Deferred[WriterT[F, L, *], A]] = ???
  }

}
