/*
 * Copyright 2020-2021 Typelevel
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

import cats.effect.kernel.{Concurrent, FiberLocal}
import cats.syntax.all._
import cats.effect.kernel.syntax.all._

trait FiberLocalLaws[F[_]] {
  implicit val F: FiberLocal[F]

  implicit def app: Concurrent[F] = F.F

  def defaultGet[A](default: A) =
    F.local(default).flatMap { local => local.get } <-> (F.local(default) >> app.pure(default))

  def setThenGet[A](default: A, other: A) =
    F.local(default).flatMap { local => local.set(other) >> local.get } <-> (F
      .local(default)
      .flatMap(_.set(other)) >> app.pure(other))

  def setIsIdempotent[A](default: A, other: A) =
    F.local(default).flatMap { local => local.set(other) >> local.set(other) } <->
      F.local(default).flatMap { local => local.set(other) }

  def copyOnFork[A](default: A) =
    F.local(default).flatMap { local => (local.get.start).flatMap(_.joinWithNever) } <-> (F
      .local(default) >> app.pure(default))

  def modificationInParent[A](default: A, other: A) =
    F.local(default).flatMap { local =>
      local.get.start.flatMap { child => local.set(other) >> child.joinWithNever }
    } <-> (F.local(default) >> app.pure(default))

  def modificationInChild[A](default: A, other: A) =
    F.local(default).flatMap { local =>
      (local.set(other).start).flatMap(_.join) >> local.get
    } <-> (F.local(default) >> app.pure(default))

  def allocateInChild[A](default: A, childDefault: A) =
    (F.local(childDefault).start.flatMap(_.join) >> F.local(default).flatMap(_.get)) <->
      (F.local(default).flatMap(_.get))
}

object FiberLocalLaws {
  def apply[F[_]](implicit F0: FiberLocal[F]): FiberLocalLaws[F] =
    new FiberLocalLaws[F] { val F = F0 }
}
