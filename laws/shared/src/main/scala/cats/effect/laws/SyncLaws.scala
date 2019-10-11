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

package cats
package effect
package laws

import cats.implicits._
import cats.laws._

trait SyncLaws[F[_]] extends BracketLaws[F, Throwable] with DeferLaws[F] {
  implicit def F: Sync[F]

  def delayConstantIsPure[A](a: A) =
    F.delay(a) <-> F.pure(a)

  def suspendConstantIsPureJoin[A](fa: F[A]) =
    F.suspend(fa) <-> F.flatten(F.pure(fa))

  def delayThrowIsRaiseError[A](e: Throwable) =
    F.delay[A](throw e) <-> F.raiseError(e)

  def suspendThrowIsRaiseError[A](e: Throwable) =
    F.suspend[A](throw e) <-> F.raiseError(e)

  def unsequencedDelayIsNoop[A](a: A, f: A => A) =
    F.suspend {
      var cur = a
      val change = F.delay { cur = f(cur) }
      val _ = change

      F.delay(cur)
    } <-> F.pure(a)

  def repeatedSyncEvaluationNotMemoized[A](a: A, f: A => A) =
    F.suspend {
      var cur = a
      val change = F.delay { cur = f(cur) }
      val read = F.delay(cur)

      change *> change *> read
    } <-> F.pure(f(f(a)))

  def propagateErrorsThroughBindSuspend[A](t: Throwable) = {
    val fa = F.delay[A](throw t).flatMap(x => F.pure(x))

    fa <-> F.raiseError(t)
  }

  def bindSuspendsEvaluation[A](fa: F[A], a1: A, f: (A, A) => A) =
    F.suspend {
      var state = a1
      val evolve = F.flatMap(fa) { a2 =>
        state = f(a1, a2)
        F.pure(state)
      }
      // Observing `state` before and after `evolve`
      F.map2(F.pure(state), evolve)(f)
    } <-> F.map(fa)(a2 => f(a1, f(a1, a2)))

  def mapSuspendsEvaluation[A](fa: F[A], a1: A, f: (A, A) => A) =
    F.suspend {
      var state = a1
      val evolve = F.map(fa) { a2 =>
        state = f(a1, a2)
        state
      }
      // Observing `state` before and after `evolve`
      F.map2(F.pure(state), evolve)(f)
    } <-> F.map(fa)(a2 => f(a1, f(a1, a2)))

  def stackSafetyOnRepeatedLeftBinds(iterations: Int) = {
    val result = (0 until iterations).foldLeft(F.delay(())) { (acc, _) =>
      acc.flatMap(_ => F.delay(()))
    }
    result <-> F.pure(())
  }

  def stackSafetyOnRepeatedRightBinds(iterations: Int) = {
    val result = (0 until iterations).foldRight(F.delay(())) { (_, acc) =>
      F.delay(()).flatMap(_ => acc)
    }
    result <-> F.pure(())
  }

  def stackSafetyOnRepeatedAttempts(iterations: Int) = {
    // Note this isn't enough to guarantee stack safety, unless
    // coupled with `bindSuspendsEvaluation`
    val result = (0 until iterations).foldLeft(F.delay(())) { (acc, _) =>
      F.attempt(acc).map(_ => ())
    }
    result <-> F.pure(())
  }

  def stackSafetyOnRepeatedMaps(iterations: Int) = {
    // Note this isn't enough to guarantee stack safety, unless
    // coupled with `mapSuspendsEvaluation`
    val result = (0 until iterations).foldLeft(F.delay(0)) { (acc, _) =>
      F.map(acc)(_ + 1)
    }
    result <-> F.pure(iterations)
  }

  def stackSafetyOfBracketOnRepeatedLeftBinds(iterations: Int) = {
    val result = (0 until iterations).foldRight(F.delay(())) { (_, acc) =>
      acc.flatMap(_ => F.bracket(F.unit)(F.pure(_))(_ => F.unit))
    }
    result <-> F.pure(())
  }

  def stackSafetyOfBracketOnRepeatedRightBinds(iterations: Int) = {
    val result = (0 until iterations).foldRight(F.delay(())) { (_, acc) =>
      F.bracket(F.unit)(F.pure(_))(_ => F.unit).flatMap(_ => acc)
    }
    result <-> F.pure(())
  }

  def stackSafetyOfGuaranteeOnRepeatedLeftBinds(iterations: Int) = {
    val result = (0 until iterations).foldRight(F.delay(())) { (_, acc) =>
      acc.flatMap(_ => F.guarantee(F.unit)(F.unit))
    }
    result <-> F.pure(())
  }

  def stackSafetyOfGuaranteeOnRepeatedRightBinds(iterations: Int) = {
    val result = (0 until iterations).foldRight(F.delay(())) { (_, acc) =>
      F.guarantee(F.unit)(F.unit).flatMap(_ => acc)
    }
    result <-> F.pure(())
  }
}

object SyncLaws {
  def apply[F[_]](implicit F0: Sync[F]): SyncLaws[F] = new SyncLaws[F] {
    val F = F0
  }
}
