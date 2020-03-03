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

package cats.effect.internals

import cats.effect.IO
import cats.effect.internals.TrampolineEC.immediate
import scala.concurrent.Future
import scala.util.{Failure, Left, Right, Success}

private[effect] object IOFromFuture {

  /**
   * Implementation for `IO.fromFuture`.
   */
  def apply[A](f: Future[A]): IO[A] =
    f.value match {
      case Some(result) =>
        result match {
          case Success(a) => IO.pure(a)
          case Failure(e) => IO.raiseError(e)
        }
      case _ =>
        IO.async { cb =>
          f.onComplete { r =>
            cb(r match {
              case Success(a) => Right(a)
              case Failure(e) => Left(e)
            })
          }(immediate)
        }
    }
}
