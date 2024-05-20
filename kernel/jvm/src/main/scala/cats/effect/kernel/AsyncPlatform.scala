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

package cats
package effect.kernel

import java.util.concurrent.{CompletableFuture, CompletionException, CompletionStage}

private[kernel] trait AsyncPlatform[F[_]] extends Serializable { this: Async[F] =>

  def fromCompletionStage[A](completionStage: F[CompletionStage[A]]): F[A] =
    fromCompletableFuture(flatMap(completionStage) { cs => delay(cs.toCompletableFuture()) })

  /**
   * Suspend a `java.util.concurrent.CompletableFuture` into the `F[_]` context.
   *
   * @note
   *   Cancelation is cooperative and it is up to the `CompletableFuture` to respond to the
   *   request by handling cancelation appropriately and indicating that it has done so. This
   *   means that if the `CompletableFuture` indicates that it did not cancel, there will be no
   *   "fire-and-forget" semantics. Instead, to satisfy backpressure guarantees, the
   *   `CompletableFuture` will be treated as if it is uncancelable and the fiber will fallback
   *   to waiting for it to complete.
   *
   * @param fut
   *   The `java.util.concurrent.CompletableFuture` to suspend in `F[_]`
   */
  def fromCompletableFuture[A](fut: F[CompletableFuture[A]]): F[A] = cont {
    new Cont[F, A, A] {
      def apply[G[_]](
          implicit
          G: MonadCancelThrow[G]): (Either[Throwable, A] => Unit, G[A], F ~> G) => G[A] = {
        (resume, get, lift) =>
          G.uncancelable { poll =>
            G.flatMap(poll(lift(fut))) { cf =>
              val go = delay {
                cf.handle[Unit] {
                  case (a, null) => resume(Right(a))
                  case (_, t) =>
                    resume(Left(t match {
                      case e: CompletionException if e.getCause ne null => e.getCause
                      case _ => t
                    }))
                }
              }

              val await = G.onCancel(
                poll(get),
                // if cannot cancel, fallback to get
                G.ifM(lift(delay(cf.cancel(false))))(G.unit, G.void(get))
              )

              G.productR(lift(go))(await)
            }
          }
      }
    }
  }

}
