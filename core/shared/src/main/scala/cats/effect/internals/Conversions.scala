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

import scala.util.{Failure, Success, Try}

/**
 * Internal API — describes internal conversions.
 */
private[effect] object Conversions {
  def toTry[A](a: Either[Throwable, A]): Try[A] =
    a match {
      case Right(r) => Success(r)
      case Left(l)  => Failure(l)
    }

  def toEither[A](a: Try[A]): Either[Throwable, A] =
    a match {
      case Success(r) => Right(r)
      case Failure(l) => Left(l)
    }
}
