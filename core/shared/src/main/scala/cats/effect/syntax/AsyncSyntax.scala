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

package cats.effect.syntax

import cats.effect.{Async, ContextShift}

import scala.concurrent.Future

trait AsyncSyntax extends Async.ToAsyncOps {

  implicit def catsEffectSyntaxAsyncObj[F[_]](F: Async[F]): AsyncObjOps[F] =
    new AsyncObjOps[F](F)

  implicit def catsEffectSyntaxAsyncFuture[F[_], A](fa: F[Future[A]]): AsyncFutureOps[F, A] =
    new AsyncFutureOps[F, A](fa)
}

final class AsyncObjOps[F[_]](private val F: Async[F]) extends AnyVal {

  /**
    * Constructs an `Async` which evaluates the given `Future` and
    * produces the result (or failure).
    *
    * Because `Future` eagerly evaluates, as well as because it
    * memoizes, this function takes its parameter as an `Async`,
    * which could be lazily evaluated.  If this laziness is
    * appropriately threaded back to the definition site of the
    * `Future`, it ensures that the computation is fully managed by
    * `Async` and thus referentially transparent.
    *
    * Example:
    *
    * {{{
    *   // Lazy evaluation, equivalent with by-name params
    *   F.fromFuture(F.delay(startRunningFuture()))
    *
    *   // Eager evaluation, for pure futures
    *   F.fromFuture(F.pure(startRunningFuture()))
    * }}}
    */
  def fromFuture[A](af: F[Future[A]])(implicit cs: ContextShift[F]): F[A] = {
    Async.fromFuture(af)(F, cs)
  }
}

final class AsyncFutureOps[F[_], A](private val af: F[Future[A]]) extends AnyVal {

  /**
    * Constructs an `Async` which evaluates the given `Future` and
    * produces the result (or failure).
    *
    * Because `Future` eagerly evaluates, as well as because it
    * memoizes, this function takes its parameter as an `Async`,
    * which could be lazily evaluated.  If this laziness is
    * appropriately threaded back to the definition site of the
    * `Future`, it ensures that the computation is fully managed by
    * `Async` and thus referentially transparent.
    *
    * Example:
    *
    * {{{
    *   // Lazy evaluation, equivalent with by-name params
    *   F.delay(startRunningFuture()).fromFutureToAsync(ec)
    *
    *   // Eager evaluation, for pure futures
    *   F.pure(startRunningFuture()).fromFutureToAsync(ec)
    * }}}
    */
  def fromFuture(implicit F: Async[F], cs: ContextShift[F]): F[A] = {
    Async.fromFuture(af)
  }
}
