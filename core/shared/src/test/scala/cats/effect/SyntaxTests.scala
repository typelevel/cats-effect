/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

import cats.data._
import cats.effect.syntax.AllCatsEffectSyntax
import cats.{Monoid, Parallel, Traverse}

import scala.concurrent.duration._

object SyntaxTests extends AllCatsEffectSyntax {
  def mock[A]: A = ???
  def typed[A](a: A): Unit = {
    val _ = a
  }

  def bracketSyntax[F[_], A, B](implicit F: Bracket[F, Throwable]) = {
    val acquire = mock[F[A]]
    val use = mock[A => F[B]]
    val releaseCase = mock[(A, ExitCase[Throwable]) => F[Unit]]
    val release = mock[A => F[Unit]]
    val finalizer = mock[F[Unit]]
    val finalCase = mock[ExitCase[Throwable] => F[Unit]]

    typed[F[A]](acquire.uncancelable)
    typed[F[B]](acquire.bracket(use)(release))
    typed[F[B]](acquire.bracketCase(use)(releaseCase))
    typed[F[A]](acquire.guarantee(finalizer))
    typed[F[A]](acquire.guaranteeCase(finalCase))
  }

  def bracketSyntaxForMonadTransformersWithSync[F[_], A, B, L, S](implicit F: Sync[F], L: Monoid[L]) = {
    typed[OptionT[F, A]](mock[OptionT[F, A]].uncancelable)
    typed[EitherT[F, A, B]](mock[EitherT[F, A, B]].uncancelable)
    typed[IorT[F, L, A]](mock[IorT[F, L, A]].uncancelable)
    typed[StateT[F, A, B]](mock[StateT[F, A, B]].uncancelable)
    typed[WriterT[F, L, A]](mock[WriterT[F, L, A]].uncancelable)
    typed[ReaderWriterStateT[F, A, L, S, B]](mock[ReaderWriterStateT[F, A, L, S, B]].uncancelable)
  }

  def asyncSyntax[T[_]: Traverse, F[_], A, B](implicit F: Async[F], P: Parallel[F]) = {
    val n = mock[Long]
    val ta = mock[T[A]]
    val f = mock[A => F[B]]
    val tma = mock[T[F[A]]]

    typed[F[T[B]]](F.parTraverseN(n)(ta)(f))
    typed[F[T[A]]](F.parSequenceN(n)(tma))
  }

  def concurrentSyntax[T[_]: Traverse, F[_], A, B](implicit F: Concurrent[F], P: Parallel[F], timer: Timer[F]) = {
    val fa = mock[F[A]]
    val fa2 = mock[F[A]]
    val fb = mock[F[B]]

    typed[F[Fiber[F, A]]](fa.start)
    typed[F[Either[A, B]]](fa.race(fb))
    typed[F[Either[(A, Fiber[F, B]), (Fiber[F, A], B)]]](fa.racePair(fb))
    typed[F[A]](fa.timeout(1.second))
    typed[F[A]](fa.timeoutTo(1.second, fa2))

    val n = mock[Long]
    val ta = mock[T[A]]
    val f = mock[A => F[B]]
    val tma = mock[T[F[A]]]

    typed[F[T[B]]](F.parTraverseN(n)(ta)(f))
    typed[F[T[A]]](F.parSequenceN(n)(tma))
  }

  def effectSyntax[F[_]: Effect, A] = {
    val fa = mock[F[A]]
    val cb = mock[Either[Throwable, A] => IO[Unit]]

    typed[IO[A]](fa.toIO)
    typed[SyncIO[Unit]](fa.runAsync(cb))
  }

  def concurrentEffectSyntax[F[_]: ConcurrentEffect, A] = {
    val fa = mock[F[A]]
    val cb = mock[Either[Throwable, A] => IO[Unit]]

    typed[SyncIO[F[Unit]]](fa.runCancelable(cb))
  }
}
