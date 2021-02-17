/*
 * Copyright (c) 2017-2021 The Typelevel Cats-effect Project Developers
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

package cats.effect.concurrent

import cats.effect._
import cats.effect.implicits._
import cats.implicits._

/**
 * Utility to apply backpressure semantics to the execution of an Effect.
 * Backpressure instances will apply a [[Backpressure.Strategy]] to the
 * execution where each strategy works as follows:
 *
 * [[Backpressure.Strategy.Lossy]] will mean that effects will not be run in
 * the presence of backpressure, meaning the result will be None
 *
 * [[Backpressure.Strategy.Lossless]] will mean that effects will run in the
 * presence of backpressure, meaning the effect will semantically block until
 * backpressure is alleviated
 */
trait Backpressure[F[_]] {

  /**
   * Applies rate limiting to an effect based on backpressure semantics
   *
   * @param f the effect that backpressure is applied to
   * @return an [[Option]] where [[Option]] denotes if the effect was run or not
   * according to backpressure semantics
   */
  def metered[A](f: F[A]): F[Option[A]]
}

object Backpressure {

  /**
   * Creates an instance of Backpressure that can be used to rate limit effects
   * @param strategy strategy to apply for this backpressure instance
   * @param bound depth of the queue that the backpressure instance should manage
   * @return a [[Backpressure]] instance
   */
  def apply[F[_]: Concurrent](
    strategy: Strategy,
    bound: Int
  ): F[Backpressure[F]] = {
    require(bound > 0)
    Semaphore[F](bound.toLong).map(sem =>
      strategy match {
        case Strategy.Lossy =>
          new Backpressure[F] {
            override def metered[A](f: F[A]): F[Option[A]] =
              sem.tryAcquire.bracket {
                case true  => f.map(_.some)
                case false => none[A].pure[F]
              } {
                case true  => sem.release
                case false => Concurrent[F].unit
              }
          }
        case Strategy.Lossless =>
          new Backpressure[F] {
            override def metered[A](f: F[A]): F[Option[A]] =
              sem.withPermit(f).map(_.some)
          }
      }
    )

  }

  sealed trait Strategy
  object Strategy {
    case object Lossy extends Strategy
    case object Lossless extends Strategy
  }
}
