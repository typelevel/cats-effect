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

package object effect {

  /**
   * A cancelation token is an effectful action that is
   * able to cancel a running task.
   *
   * This is just an alias in order to clarify the API.
   * For example seeing `CancelToken[IO]` instead of `IO[Unit]`
   * can be more readable.
   *
   * Cancelation tokens usually have these properties:
   *
   *  1. they suspend over side effectful actions on shared state
   *  1. they need to be idempotent
   *
   * Note that in the case of well behaved implementations like
   * that of [[IO]] idempotency is taken care of by its internals
   * whenever dealing with cancellation tokens, but idempotency
   * is a useful property to keep in mind when building such values.
   */
  type CancelToken[F[_]] = F[Unit]

  /**
   * Provides missing methods on Scala 2.11's Either while allowing
   * -Xfatal-warnings along with -Ywarn-unused-import
   */
  @deprecated("Cats-effect no longer supports Scala 2.11.x", "2.1.0")
  implicit private[effect] class scala211EitherSyntax[A, B](val self: Either[A, B]) extends AnyVal {
    def map[B2](f: B => B2): Either[A, B2] = self match {
      case l @ Left(_) => l.asInstanceOf[Either[A, B2]]
      case Right(b)    => Right(f(b))
    }

    def flatMap[B2](f: B => Either[A, B2]): Either[A, B2] = self match {
      case Right(a)    => f(a)
      case l @ Left(_) => l.asInstanceOf[Either[A, B2]]
    }
  }
}
