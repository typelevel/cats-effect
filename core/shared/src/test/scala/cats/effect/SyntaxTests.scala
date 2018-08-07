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

import cats.effect.syntax.AllCatsEffectSyntax
import scala.concurrent.duration._

object SyntaxTests extends AllCatsEffectSyntax {
  def mock[A]: A = ???
  def typed[A](a: A): Unit = ()

  def bracketSyntax[F[_]: Bracket[?[_], Throwable], A, B] = {
    val acquire     = mock[F[A]]
    val use         = mock[A => F[B]]
    val releaseCase = mock[(A, ExitCase[Throwable]) => F[Unit]]
    val release     = mock[A => F[Unit]]
    val finalizer   = mock[F[Unit]]
    val finalCase   = mock[ExitCase[Throwable] => F[Unit]]

    typed[F[A]](acquire.uncancelable)
    typed[F[B]](acquire.bracket(use)(release))
    typed[F[B]](acquire.bracketCase(use)(releaseCase))
    typed[F[A]](acquire.guarantee(finalizer))
    typed[F[A]](acquire.guaranteeCase(finalCase))
  }

  def concurrentSyntax[F[_]: Concurrent, A, B](implicit timer: Timer[F]) = {
    val fa  = mock[F[A]]
    val fa2 = mock[F[A]]
    val fb  = mock[F[B]]

    typed[F[Fiber[F, A]]](fa.start)
    typed[F[Either[A, B]]](fa.race(fb))
    typed[F[Either[(A, Fiber[F, B]), (Fiber[F, A], B)]]](fa.racePair(fb))
    typed[F[A]](fa.timeout(1.second))
    typed[F[A]](fa.timeoutTo(1.second, fa2))
  }
  
  def effectSyntax[F[_]: Effect, A] = {
    val fa = mock[F[A]]
    val cb = mock[Either[Throwable, A] => IO[Unit]]

    typed[IO[A]](fa.toIO)
    typed[SyncIO[Unit]](fa.runAsync(cb))
    typed[SyncIO[Either[F[A], A]]](fa.runSyncStep)
  }

  def concurrentEffectSyntax[F[_]: ConcurrentEffect, A] = {
    val fa = mock[F[A]]
    val cb = mock[Either[Throwable, A] => IO[Unit]]

    typed[SyncIO[F[Unit]]](fa.runCancelable(cb))
  }
}
