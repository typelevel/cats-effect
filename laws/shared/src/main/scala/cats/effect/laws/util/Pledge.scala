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

package cats.effect.laws.util

import cats.effect.{Async, Sync}
import cats.effect.internals.TrampolineEC

import scala.concurrent.Promise
import scala.util.{Failure, Success}

/**
 * `Pledge` is a value that can be completed later, much like a
 * Scala `Promise`, but pure, to be used in describing laws that
 * require the observation of side effects.
 *
 * The internal implementation effectively wraps a Scala `Promise`,
 * so it has the same contract:
 *
 *  1. it can only be "completed" once, being a write-once value
 *  1. once written, the value is memoized for subsequent reads
 *
 * Example:
 *
 * {{{
 *   for {
 *     pledge <- Pledge[IO, Int]
 *     fiber <- pledge.await[IO].start
 *     _ <- pledge.complete[IO](100)
 *     result <- fiber.join
 *   } yield {
 *     result
 *   }
 * }}}
 */
final class Pledge[A] {
  private[this] val ref = Promise[A]()

  /**
   * Completes this pledge with the given value.
   *
   * It's a protocol violation to try and complete the
   * pledge twice. The second time will end in error.
   */
  def complete[F[_]](a: A)(implicit F: Sync[F]): F[Unit] =
    F.delay(ref.success(a))

  /**
   * Awaits for the pledge to be completed, then returns
   * the final value.
   */
  def await[F[_]](implicit F: Async[F]): F[A] =
    F.async { cb =>
      implicit val ec = TrampolineEC.immediate
      ref.future.onComplete {
        case Success(a) => cb(Right(a))
        case Failure(e) => cb(Left(e))
      }
    }
}

object Pledge {
  /**
   * Builder for a `Pledge` instance.
   */
  def apply[F[_], A](implicit F: Async[F]): F[Pledge[A]] =
    F.delay(new Pledge[A]())
}
