/*
 * Copyright 2020 Typelevel
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
package unsafe

import scala.concurrent.{ExecutionContext, Future, Promise}

@annotation.implicitNotFound("""Could not find an implicit IORuntime.

Instead of calling unsafe methods directly, consider using cats.effect.IOApp, which
runs your IO. If integrating with non-functional code or experimenting in a REPL / Worksheet,
add the following import:

import cats.effect.unsafe.implicits.global

Alternatively, you can create an explicit IORuntime value and put it in implicit scope.
This may be useful if you have a pre-existing fixed thread pool and/or scheduler which you
wish to use to execute IO programs. Please be sure to review thread pool best practices to
avoid unintentionally degrading your application performance.
""")
final class IORuntime private[effect] (
    val compute: IOExecutionContext,
    val blocking: ExecutionContext,
    val scheduler: Scheduler,
    val shutdown: () => Unit) {

  private implicit val self: IORuntime = this

  val unsafeRunForIO: UnsafeRun[IO] =
    new UnsafeRun[IO] {
      def unsafeRunFutureCancelable[A](fa: IO[A]): (Future[A], () => Future[Unit]) = {
        val p = Promise[A]()
        val fiber = fa.unsafeRunFiber(true) {
          case Left(t) => p.failure(t)
          case Right(a) => p.success(a)
        }
        (p.future, () => fiber.cancel.unsafeToFuture())
      }
    }

  override def toString: String = s"IORuntime($compute, $scheduler)"
}

object IORuntime extends IORuntimeCompanionPlatform {
  def apply(
      compute: ExecutionContext,
      blocking: ExecutionContext,
      scheduler: Scheduler,
      shutdown: () => Unit): IORuntime =
    new IORuntime(IOExecutionContext.wrap(compute), blocking, scheduler, shutdown)
}
