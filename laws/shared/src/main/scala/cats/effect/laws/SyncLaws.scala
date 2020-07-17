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

package cats.effect
package laws

import cats.effect.kernel.Sync
import cats.implicits._
import cats.laws.MonadErrorLaws

trait SyncLaws[F[_]] extends MonadErrorLaws[F, Throwable] with ClockLaws[F] {

  implicit val F: Sync[F]

  def delayValueIsPure[A](a: A) =
    F.delay(a) <-> F.pure(a)

  def delayThrowIsRaiseError[A](e: Throwable) =
    F.delay[A](throw e) <-> F.raiseError(e)

  def unsequencedDelayIsNoop[A](a: A, f: A => A) = {
    val isWith = F.delay {
      var cur = a
      val _ = F.delay { cur = f(cur) }
      F.delay(cur)
    }

    val isWithout = F.delay {
      var cur = a
      cur = a // no-op to confuse the compiler
      F.delay(cur)
    }

    isWith.flatten <-> isWithout.flatten
  }

  def repeatedDelayNotMemoized[A](a: A, f: A => A) = {
    val isWith = F.delay {
      var cur = a

      val changeF = F.delay {
        cur = f(cur)
        cur
      }

      changeF >> changeF
    }

    val isWithout = F.delay {
      var cur = a

      F.delay {
        cur = f(f(cur))
        cur
      }
    }

    isWith.flatten <-> isWithout.flatten
  }
}

object SyncLaws {
  def apply[F[_]](implicit F0: Sync[F]): SyncLaws[F] =
    new SyncLaws[F] { val F = F0 }
}
