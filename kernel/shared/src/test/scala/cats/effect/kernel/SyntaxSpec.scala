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

import cats.implicits._

import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}

class SyntaxSpec extends Specification {

  "kernel syntax" >> ok

  def concurrentForwarder[F[_]: Concurrent] =
    Concurrent[F]

  def genConcurrentSyntax[F[_], E, A](target: F[A])(implicit F: GenConcurrent[F, E]) = {
    import syntax.concurrent._

    GenConcurrent[F]: F.type
    GenConcurrent[F, E]: F.type

    {
      val result = target.memoize
      result: F[F[A]]
    }

    {
      val result = List(1).parTraverseN(3)(F.pure)
      result: F[List[Int]]
    }

    {
      val result = List(1).parTraverseN_(3)(F.pure)
      result: F[Unit]
    }

    {
      val result = List(target).parSequenceN(3)
      result: F[List[A]]
    }

    {
      val result = List(target).parSequenceN_(3)
      result: F[Unit]
    }

    {
      val result = target.parReplicateAN(3)(5)
      result: F[List[A]]
    }
  }

  def monadCancelSyntax[F[_], A, E](target: F[A])(implicit F: MonadCancel[F, E]) = {
    import syntax.monadCancel._

    MonadCancel[F]: F.type
    MonadCancel[F, E]: F.type

    {
      val result = target.forceR(F.unit)
      result: F[Unit]
    }

    {
      val result = target !> F.unit
      result: F[Unit]
    }

    {
      val result = target.uncancelable
      result: F[A]
    }

    {
      val result = target.onCancel(F.unit)
      result: F[A]
    }

    {
      val result = target.guarantee(F.unit)
      result: F[A]
    }

    {
      val result = target.bracket(_ => F.unit)(_ => F.unit)
      result: F[Unit]
    }

    {
      val result = target.guaranteeCase(_ => F.unit)
      result: F[A]
    }

    {
      val result = target.bracketCase(_ => F.unit)((_, _) => F.unit)
      result: F[Unit]
    }
  }

  def genSpawnSyntax[F[_], A, B, E](target: F[A], another: F[B])(implicit F: GenSpawn[F, E]) = {
    import syntax.spawn._

    GenSpawn[F]: F.type
    GenSpawn[F, E]: F.type

    {
      val result = target.start
      result: F[Fiber[F, E, A]]
    }

    {
      val result = target.background
      result: Resource[F, F[Outcome[F, E, A]]]
    }

    {
      val result = target.race(another)
      result: F[Either[A, B]]
    }

    {
      val result = target.raceOutcome(another)
      result: F[Either[Outcome[F, E, A], Outcome[F, E, B]]]
    }

    {
      val result = target.both(another)
      result: F[(A, B)]
    }

    {
      val result = target.bothOutcome(another)
      result: F[(Outcome[F, E, A], Outcome[F, E, B])]
    }
  }

  def spawnForwarder[F[_]: Spawn] =
    Spawn[F]

  def genTemporalSyntax[F[_], A, E](target: F[A])(implicit F: GenTemporal[F, E]) = {
    import syntax.temporal._

    GenTemporal[F]: F.type
    GenTemporal[F, E]: F.type

    {
      val param1: FiniteDuration = null.asInstanceOf[FiniteDuration]
      val param2: F[A] = null.asInstanceOf[F[A]]
      val result = target.timeoutTo(param1, param2)
      result: F[A]
    }

    {
      val param1: Duration = null.asInstanceOf[Duration]
      val param2: F[A] = null.asInstanceOf[F[A]]
      val result = target.timeoutTo(param1, param2)
      result: F[A]
    }

    {
      val param: FiniteDuration = null.asInstanceOf[FiniteDuration]
      val result = target.delayBy(param)
      result: F[A]
    }

    {
      val param: FiniteDuration = null.asInstanceOf[FiniteDuration]
      val result = target.andWait(param)
      result: F[A]
    }
  }

  def temporalSyntax[F[_], A](target: F[A])(implicit F: Temporal[F]) = {
    import syntax.temporal._

    Temporal[F]: F.type

    {
      val param: FiniteDuration = null.asInstanceOf[FiniteDuration]
      val result = target.timeout(param)
      result: F[A]
    }

    {
      val param: Duration = null.asInstanceOf[Duration]
      val result = target.timeout(param)
      result: F[A]
    }
  }

  def asyncSyntax[F[_], A](target: F[A])(implicit F: Async[F]) = {
    import syntax.async._

    Async[F]: F.type

    {
      val param: ExecutionContext = null.asInstanceOf[ExecutionContext]
      val result = target.evalOn(param)
      result: F[A]
    }
  }

  def resourceSyntax[F[_], A](target: F[A]) = {
    import syntax.resource._
    {
      val result = target.toResource
      result: Resource[F, A]
    }
  }

  def clockSyntax[F[_], A](target: F[A])(implicit F: Clock[F]) = {
    import syntax.clock._

    Clock[F]: F.type

    {
      val result = target.timed
      result: F[(FiniteDuration, A)]
    }
  }
}
