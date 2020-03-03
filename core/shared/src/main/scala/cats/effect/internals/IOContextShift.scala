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

import cats.effect.{ContextShift, IO}

import scala.concurrent.ExecutionContext

/**
 * Internal API — `ContextShift[IO]` implementation.
 *
 * Depends on having a Scala `ExecutionContext` for the actual
 * execution of tasks (i.e. bind continuations)
 */
final private[internals] class IOContextShift private (ec: ExecutionContext) extends ContextShift[IO] {
  val shift: IO[Unit] =
    IOShift(ec)

  override def evalOn[A](context: ExecutionContext)(f: IO[A]): IO[A] =
    IOShift.shiftOn(context, ec, f)
}

private[effect] object IOContextShift {

  /** `ContextShift` builder. */
  def apply(ec: ExecutionContext): ContextShift[IO] =
    new IOContextShift(ec)

  /** Global instance, used in `IOApp` on JavaScript (see: PoolUtils for the JVM semantics). */
  lazy val global: ContextShift[IO] =
    apply(ExecutionContext.Implicits.global)
}
