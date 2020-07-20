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

import org.specs2.mutable.Specification

import scala.concurrent.{ExecutionContext, TimeoutException}
import scala.concurrent.duration.FiniteDuration

class SyntaxSpec extends Specification {

  "kernel syntax" >> ok

  def concurrentSyntax[F[_], A, E](target: F[A])(implicit F: Concurrent[F, E]) = {
    import cats.effect.syntax.concurrent._

    Concurrent[F]: F.type
    Concurrent[F, E]: F.type

    {
      val result = target.start
      result: F[Fiber[F, E, A]]
    }

    {
      val result = target.uncancelable
      result: F[A]
    }

    {
      val param: F[Unit] = null.asInstanceOf[F[Unit]]
      val result = target.onCancel(param)
      result: F[A]
    }

    {
      val param: F[Unit] = null.asInstanceOf[F[Unit]]
      val result = target.guarantee(param)
      result: F[A]
    }

    {
      val param: Outcome[F, E, A] => F[Unit] = null.asInstanceOf[Outcome[F, E, A] => F[Unit]]
      val result = target.guaranteeCase(param)
      result: F[A]
    }
  }

  def temporalSyntax[F[_], A, E](target: F[A])(implicit F: Temporal[F, E]) = {
    import cats.effect.syntax.temporal._

    Temporal[F]: F.type
    Temporal[F, E]: F.type

    {
      val param1: FiniteDuration = null.asInstanceOf[FiniteDuration]
      val param2: F[A] = null.asInstanceOf[F[A]]
      val result = target.timeoutTo(param1, param2)
      result: F[A]
    }
  }

  def temporalThrowSyntax[F[_], A](target: F[A])(implicit F: Temporal[F, Throwable]) = {
    import cats.effect.syntax.temporal._

    {
      val param: FiniteDuration = null.asInstanceOf[FiniteDuration]
      val result = target.timeout(param)
      result: F[A]
    }
  }

  def temporalThrowRuntimeSyntax[F[_], A](target: F[A])(implicit F: Temporal[F, TimeoutException]) = {
    import cats.effect.syntax.temporal._

    {
      val param: FiniteDuration = null.asInstanceOf[FiniteDuration]
      val result = target.timeout(param)
      result: F[A]
    }
  }

  def asyncSyntax[F[_], A](target: F[A])(implicit F: Async[F]) = {
    import cats.effect.syntax.async._

    Async[F]: F.type

    {
      val param: ExecutionContext = null.asInstanceOf[ExecutionContext]
      val result = target.evalOn(param)
      result: F[A]
    }
  }

  def syncEffectSyntax[F[_], G[_], A](target: F[A])(implicit F: SyncEffect[F], G: SyncEffect[G]) = {
    import cats.effect.syntax.syncEffect._

    SyncEffect[F]: F.type

    {
      val result = target.to[G]
      result: G[A]
    }
  }

  def effectSyntax[F[_], G[_], A](target: F[A])(implicit F: Effect[F], G: Effect[G]) = {
    import cats.effect.syntax.effect._

    Effect[F]: F.type

    {
      val result = target.to[G]
      result: G[A]
    }
  }

  def allSyntax[F[_], G[_], A](target: F[A])(implicit F: Effect[F], G: Effect[G]) = {
    import cats.effect.syntax.all._

    {
      val result = target.to[G]
      result: G[A]
    }
  }
}
