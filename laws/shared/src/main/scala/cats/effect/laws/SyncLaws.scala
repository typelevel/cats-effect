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

package cats.effect
package laws

import cats.effect.kernel.Sync
import cats.syntax.all._

trait SyncLaws[F[_]]
    extends MonadCancelLaws[F, Throwable]
    with ClockLaws[F]
    with UniqueLaws[F] {

  implicit val F: Sync[F]

  def suspendValueIsPure[A](a: A, hint: Sync.Type) =
    F.suspend(hint)(a) <-> F.pure(a)

  def suspendThrowIsRaiseError[A](e: Throwable, hint: Sync.Type) =
    F.suspend[A](hint)(throw e) <-> F.raiseError(e)

  def unsequencedSuspendIsNoop[A](a: A, f: A => A, hint: Sync.Type) = {
    val isWith = F delay {
      var cur = a
      val _ = F.suspend(hint) { cur = f(cur) }
      F.delay(cur)
    }

    val isWithout = F delay {
      var cur = a
      cur = a // no-op to confuse the compiler
      F.delay(cur)
    }

    isWith.flatten <-> isWithout.flatten
  }

  def repeatedSuspendNotMemoized[A](a: A, f: A => A, hint: Sync.Type) = {
    val isWith = F delay {
      var cur = a

      val changeF = F.suspend(hint) {
        cur = f(cur)
        cur
      }

      changeF >> changeF
    }

    val isWithout = F delay {
      var cur = a

      F.suspend(hint) {
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
