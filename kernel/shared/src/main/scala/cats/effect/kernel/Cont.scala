/*
 * Copyright 2020-2024 Typelevel
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

package cats.effect.kernel

import cats.~>

/**
 * This construction supports `Async.cont`
 * {{{
 * trait Async[F[_]] {
 *   ...
 *
 *   def cont[A](body: Cont[F, A]): F[A]
 * }
 * }}}
 * It's a low level operation meant for implementors, end users should use `async`, `start` or
 * `Deferred` instead, depending on the use case.
 *
 * It can be understood as providing an operation to resume an `F` asynchronously, of type
 * `Either[Throwable, A] => Unit`, and an (interruptible) operation to semantically block until
 * resumption, of type `F[A]`. We will refer to the former as `resume`, and the latter as `get`.
 *
 * These two operations capture the essence of fiber blocking, and can be used to build `async`,
 * which in turn can be used to build `Fiber`, `start`, `Deferred` and so on.
 *
 * Refer to the default implementation to `Async[F].async` for an example of usage.
 *
 * The reason for the shape of the `Cont` construction in `Async[F].cont`, as opposed to simply:
 *
 * {{{
 * trait Async[F[_]] {
 *   ...
 *
 *   def cont[A]: F[(Either[Throwable, A] => Unit, F[A])]
 * }
 * }}}
 *
 * is that it's not safe to use concurrent operations such as `get.start`.
 *
 * The `Cont` encoding therefore simulates higher-rank polymorphism to ensure that you can not
 * call `start` on `get`, but only use operations up to `MonadCancel` (`flatMap`, `onCancel`,
 * `uncancelable`, etc).
 *
 * If you are an implementor, and you have an implementation of `async` but not `cont`, you can
 * override `Async[F].async` with your implementation, and use `Async.defaultCont` to implement
 * `Async[F].cont`.
 */
trait Cont[F[_], K, R] extends Serializable {
  def apply[G[_]](
      implicit
      G: MonadCancel[G, Throwable]): (Either[Throwable, K] => Unit, G[K], F ~> G) => G[R]
}
