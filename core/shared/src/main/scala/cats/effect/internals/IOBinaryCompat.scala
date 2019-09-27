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

package cats.effect
package internals

import cats.effect

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Left, Right, Success}

/**
 * INTERNAL API — trait that can provide methods on `IO` for keeping
 * binary compatibility, while at the same time restricting source
 * compatibility.
 */
private[effect] trait IOBinaryCompat[+A] { self: IO[A] =>

  /**
   * DEPRECATED — signature changed to using [[LiftIO]], to make it more
   * generic.
   *
   * This old variant is kept in order to keep binary compatibility
   * until 1.0 — when it will be removed completely.
   */
  @deprecated("Signature changed to require LiftIO", "0.10")
  private[internals] def to[F[_]](F: effect.Async[F]): F[A @uncheckedVariance] =
    // $COVERAGE-OFF$
    effect.Async.liftIO(self)(F)
  // $COVERAGE-ON$
}

private[effect] trait IOCompanionBinaryCompat {

  /**
   * DEPRECATED — the `ec` parameter is gone.
   *
   * This old variant is kept in order to keep binary compatibility
   * until 1.0 — when it will be removed completely.
   */
  @deprecated("ExecutionContext parameter is being removed", "0.10")
  private[internals] def fromFuture[A](iof: IO[Future[A]])(implicit ec: ExecutionContext): IO[A] =
    // $COVERAGE-OFF$
    iof.flatMap { f =>
      IO.async { cb =>
        f.onComplete { r =>
          cb(r match {
            case Success(a) => Right(a)
            case Failure(e) => Left(e)
          })
        }
      }
    }
  // $COVERAGE-ON$
}
