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

package cats.effect

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.blocking
import scala.concurrent.duration._

import java.util.concurrent.{ArrayBlockingQueue, CompletableFuture, TimeUnit}

abstract private[effect] class IOPlatform[+A] extends Serializable { self: IO[A] =>

  /**
   * Produces the result by running the encapsulated effects as impure side effects.
   *
   * If any component of the computation is asynchronous, the current thread will block awaiting
   * the results of the async computation. By default, this blocking will be unbounded. To limit
   * the thread block to some fixed time, use `unsafeRunTimed` instead.
   *
   * Any exceptions raised within the effect will be re-thrown during evaluation.
   *
   * As the name says, this is an UNSAFE function as it is impure and performs side effects, not
   * to mention blocking, throwing exceptions, and doing other things that are at odds with
   * reasonable software. You should ideally only call this function *once*, at the very end of
   * your program.
   */
  final def unsafeRunSync()(implicit runtime: unsafe.IORuntime): A =
    unsafeRunTimed(Long.MaxValue.nanos).get

  /**
   * Similar to `unsafeRunSync`, except with a bounded blocking duration when awaiting
   * asynchronous results. As soon as an async blocking limit is hit, evaluation ''immediately''
   * aborts and `None` is returned. Note that this does not run finalizers, which makes it quite
   * different (and less safe) than other mechanisms for limiting evaluation time.
   *
   * {{{
   * val program: IO[A] = ...
   *
   * program.timeout(5.seconds).unsafeRunSync()
   * program.unsafeRunTimed(5.seconds)
   * }}}
   *
   * The first line will run `program` for at most five seconds, interrupt the calculation, and
   * run the finalizers for as long as they need to complete. The second line will run `program`
   * for at most five seconds and then immediately release the latch, without interrupting
   * `program`'s ongoing execution.
   *
   * In other words, this function probably doesn't do what you think it does, and you probably
   * don't want to use it outside of tests.
   *
   * @see
   *   [[unsafeRunSync]]
   * @see
   *   [[IO.timeout]] for pure and safe version
   */
  final def unsafeRunTimed(limit: FiniteDuration)(
      implicit runtime: unsafe.IORuntime): Option[A] = {
    val queue = new ArrayBlockingQueue[Either[Throwable, A]](1)

    val fiber = unsafeRunAsyncImpl { r =>
      queue.offer(r)
      ()
    }

    try {
      val result = blocking(queue.poll(limit.toNanos, TimeUnit.NANOSECONDS))
      if (result eq null) None else result.fold(throw _, Some(_))
    } catch {
      case _: InterruptedException =>
        None
    } finally {
      if (IOFiberConstants.ioLocalPropagation)
        IOLocal.setThreadLocalState(fiber.getLocalState())
    }
  }

  final def unsafeToCompletableFuture()(
      implicit runtime: unsafe.IORuntime): CompletableFuture[A @uncheckedVariance] = {
    val cf = new CompletableFuture[A]()

    unsafeRunAsync {
      case Left(t) =>
        cf.completeExceptionally(t)
        ()

      case Right(a) =>
        cf.complete(a)
        ()
    }

    cf
  }
}
