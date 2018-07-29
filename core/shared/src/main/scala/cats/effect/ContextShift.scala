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

package cats.effect

import scala.concurrent.ExecutionContext

/**
  * ContextShift provides access to asynchronous execution.
  *
  * It allows to shift execution to asynchronous boundary,
  * and is capable of temporarily execute supplied computation on another ExecutionContext.
  *
  * This is NOT a type class, as it does not have the coherence
  * requirement.
  */
trait ContextShift[F[_]] {


  /**
    * Asynchronous boundary described as an effectful `F[_]` that
    * can be used in `flatMap` chains to "shift" the continuation
    * of the run-loop to another thread or call stack.
    *
    * This is the [[Async.shift]] operation, without the need for an
    * `ExecutionContext` taken as a parameter.
    *
    * This `shift` operation can usually be derived from `sleep`:
    *
    * {{{
    *   timer.shift <-> timer.sleep(Duration.Zero)
    * }}}
    */
  def shift: F[Unit]


  /**
    * Shifts execution of `f` to supplied execution context and back to default
    * context.
    *
    * This is useful in scenarios where some context has to be executed on different
    * Thread pool and once supplied `f` finishes its execution (including a failure)
    * this will return back to original execution context.
    *
    * It is useful, when `f` contains some blocking operations that need to run
    * out of constant Thread pool that usually back the `ContextShift` implementation.
    *
    * @param context  Execution content where the `f` has to be scheduled
    * @param f        Computation to rin on `context`
    */
  def shiftOn[A](context: ExecutionContext)(f: F[A]): F[A]

}


object ContextShift {

  /**
    * For a given `F` data type fetches the implicit [[ContextShift]]
    * instance available implicitly in the local scope.
    */
  def apply[F[_]](implicit contextShift: ContextShift[F]): ContextShift[F] = contextShift

  /**
    * Derives a [[ContextShift]] for any type that has a [[Effect]] instance,
    * from the implicitly available `ContextShift[IO]` that should be in scope.
    */
  def derive[F[_]](implicit F: Effect[F], contextShift: ContextShift[IO]): ContextShift[F] =
    new ContextShift[F] {
      def shift: F[Unit] =
        F.liftIO(contextShift.shift)

      def shiftOn[A](context: ExecutionContext)(f: F[A]): F[A] =
        F.liftIO(contextShift.shiftOn(context)(F.toIO(f)))
    }



}
