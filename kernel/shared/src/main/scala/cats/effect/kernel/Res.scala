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

import cats._, syntax.all._
import cats.effect.kernel._

object Ex {
  type Canc[F[_]] = MonadCancel[F, Throwable]

  type ExitCase

  trait Resource[F[_], A]
  case class Allocate[F[_], A](v: Poll[F] => F[(A, ExitCase => F[Unit])]) extends Resource[F, A]

  def mapK[F[_], G[_], A](fa: Resource[F, A], lift: F ~> G)(implicit F: Canc[F], G: Canc[G]): Resource[G, A] =  fa match {
    case Allocate(v) =>
      Allocate[G, A] { (gpoll: Poll[G]) =>
        gpoll {
          lift {
            F.uncancelable { (fpoll: Poll[F]) => v(fpoll) }
          }
        }.map { case (a, release) =>
            a -> ((r: ExitCase) => lift(release(r)))
        }
      }
  }
}
