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

package cats.effect.internals

import cats.effect.internals.IOShift.Tick
import cats.effect.{ContextShift, IO}

import scala.concurrent.ExecutionContext
import scala.scalajs.js


/**
  * Internal API — JavaScript specific implementation for a [[ContextShift]]
  * powered by `IO`.
  *
  * Deferring to JavaScript's own `setImmediate` for
  * `shift` and `shiftOn`, if available (`setImmediate` is not standard, but is available
  * on top of Node.js and has much better performance since `setTimeout`
  * introduces latency even when the specified delay is zero).
  */
private[internals] class IOContextShift extends ContextShift[IO] {
  import IOContextShift.setImmediateRef

  final def shift: IO[Unit] =
    IO.Async(new IOForkedStart[Unit] {
      def apply(conn: IOConnection, cb: Callback.T[Unit]): Unit = {
        execute(new Tick(cb))
      }
    })

  final def evalOn[A](context: ExecutionContext)(f: IO[A]): IO[A] = {
    // this consults context and then fallbacks to specialized `shift` of JS platform
    IO.async[Unit] { cb => context.execute(new Tick(cb)) }.flatMap { _ =>
      f.attempt.flatMap { r => shift.flatMap { _ => IO.fromEither(r) } }
    }
  }

  protected def execute(r: Runnable): Unit = {
    setImmediateRef(() =>
      try r.run()
      catch { case e: Throwable => e.printStackTrace() })
  }
}


object IOContextShift {

  val global: ContextShift[IO]  = new IOContextShift

  // N.B. setImmediate is not standard
  private final val setImmediateRef: js.Dynamic = {
    if (!js.isUndefined(js.Dynamic.global.setImmediate))
      js.Dynamic.global.setImmediate
    else
      js.Dynamic.global.setTimeout
  }


  /**
    * Returns an implementation that defers execution of the
    * `shift` operation to an underlying `ExecutionContext`.
    */
  def deferred(ec: ExecutionContext): ContextShift[IO] =
    new IOContextShift {
      override def execute(r: Runnable): Unit =
        ec.execute(r)
    }

}