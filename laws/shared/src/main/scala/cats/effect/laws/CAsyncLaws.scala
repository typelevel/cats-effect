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
package laws

import cats.laws._
import cats.syntax.all._

trait CAsyncLaws[F[_]] extends AsyncLaws[F] {
  implicit def F: CAsync[F]

  def asyncCancelableCoherence[A](r: Either[Throwable, A]) = {
    F.async[A](cb => cb(r)) <-> F.cancelable[A] { cb => cb(r); IO.unit }
  }

  def startJoinIsIdentity[A](fa: F[A]) =
    F.start(fa).flatMap(_.join) <-> fa

  def joinIsIdempotent[A](a: A, f: (A, A) => A) = {
    var effect = a
    val fa = F.delay { effect = f(effect, a); effect }
    F.start(fa).flatMap(t => t.join *> t.join) <-> F.pure(f(a, a))
  }

  def startCancelIsUnit[A](fa: F[A]) = {
    F.start(fa).flatMap(_.cancel) <-> F.unit
  }
}

object CAsyncLaws {
  def apply[F[_]](implicit F0: CAsync[F]): CAsyncLaws[F] = new CAsyncLaws[F] {
    val F = F0
  }
}
