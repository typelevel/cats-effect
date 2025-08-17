/*
 * Copyright 2020-2025 Typelevel
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

package cats.effect.std

import cats.effect.kernel.{Concurrent, MonadCancel, Ref, Resource}
import cats.effect.kernel.Resource.ExitCase.Succeeded
import cats.syntax.all._

/**
 * A concurrent data structure that exposes a linear sequence of `R` resources as a single
 * [[cats.effect.kernel.Resource]] in `F` without accumulation.
 *
 * A [[NonEmptyHotswap]] is allocated within a [[cats.effect.kernel.Resource]] that dictates the
 * scope of its lifetime. After creation, a `Resource[F, R]` can be swapped in by calling
 * [[swap]]. The newly acquired resource is returned and is released either when the
 * [[NonEmptyHotswap]] is finalized or upon the next call to [[swap]], whichever occurs first.
 *
 * The following diagram illustrates the linear allocation and release of three resources `r1`,
 * `r2`, and `r3` cycled through [[NonEmptyHotswap]]:
 *
 * {{{
 * create(r1) ----- swap(r2) ---- swap(r3) ---- X
 * |                |             |             |
 * r1 acquired      |             |             |
 *                  r2 acquired   |             |
 *                  r1 released   r3 acquired   |
 *                                r2 released   |
 *                                              r3 released
 * }}}
 *
 * [[NonEmptyHotswap]] is particularly useful when working with effects that cycle through
 * resources, like writing bytes to files or rotating files every N bytes or M seconds. Without
 * [[NonEmptyHotswap]], such effects leak resources: on each file rotation, a file handle or
 * some internal resource handle accumulates. With [[NonEmptyHotswap]], the only registered
 * resource is the [[NonEmptyHotswap]] itself, and each file is swapped in only after swapping
 * the previous one out.
 *
 * Replaces the deprecated [[Hotswap]] with a safer API.
 */
sealed trait NonEmptyHotswap[F[_], R] {

  /**
   * Allocates a new resource and closes the previous one.
   *
   * When the lifetime of the [[NonEmptyHotswap]] is completed, the resource allocated by the
   * most recent [[swap]] will be finalized.
   *
   * [[swap]] finalizes the previous resource immediately, so users must ensure that the old `R`
   * is not used thereafter. Failure to do so may result in an error on the _consumer_ side. In
   * any case, no resources will be leaked.
   *
   * To access the current resource, use [[get]], which guarantees that it will not be released
   * while it is being used.
   *
   * If [[swap]] is called after the lifetime of the [[NonEmptyHotswap]] is over, it will raise
   * an error, but will ensure that all resources are finalized before returning.
   */
  def swap(next: Resource[F, R]): F[Unit]

  /**
   * Gets the current resource. The returned resource is guaranteed to be available for the
   * duration of the returned resource.
   */
  def get: Resource[F, R]
}

object NonEmptyHotswap {

  /**
   * Creates a new [[NonEmptyHotswap]] initialized with the specified resource, which represents
   * a [[cats.effect.kernel.Resource]] that can be swapped during the lifetime of this
   * [[NonEmptyHotswap]].
   */
  def apply[F[_], R](initial: Resource[F, R])(
      implicit F: Concurrent[F]): Resource[F, NonEmptyHotswap[F, R]] =
    Resource.eval(Semaphore[F](Long.MaxValue)).flatMap { semaphore =>
      sealed abstract class State
      case class Acquired(r: R, fin: F[Unit]) extends State
      case object Finalized extends State

      def initialize: F[Ref[F, State]] =
        F.uncancelable { poll =>
          poll(initial.allocated).flatMap {
            case (r, fin) =>
              exclusive.mapK(poll).onCancel(Resource.eval(fin)).surround {
                F.ref(Acquired(r, fin))
              }
          }
        }

      def finalize(state: Ref[F, State]): F[Unit] =
        state.getAndSet(Finalized).flatMap {
          case Acquired(_, finalizer) => exclusive.surround(finalizer)
          case Finalized => raise("NonEmptyHotswap already finalized")
        }

      def raise[A](message: String): F[A] =
        F.raiseError[A](new IllegalStateException(message))

      def exclusive: Resource[F, Unit] =
        Resource.makeFull[F, Unit](poll => poll(semaphore.acquireN(Long.MaxValue)))(_ =>
          semaphore.releaseN(Long.MaxValue))

      Resource.make(initialize)(finalize).map { state =>
        new NonEmptyHotswap[F, R] {

          override def swap(next: Resource[F, R]): F[Unit] =
            F.uncancelable { poll =>
              poll(next.allocated).flatMap {
                case (r, fin) =>
                  exclusive.mapK(poll).onCancel(Resource.eval(fin)).surround {
                    swapFinalizer(Acquired(r, fin))
                  }
              }
            }

          override def get: Resource[F, R] =
            Resource.makeFull[F, R] { poll =>
              poll(semaphore.acquire) *> // acquire shared lock
                state.get.flatMap {
                  case Acquired(r, _) => F.pure(r)
                  case _ => raise("Hotswap already finalized")
                }
            }(_ => semaphore.release)

          private def swapFinalizer(next: State): F[Unit] =
            state.flatModify {
              case Acquired(_, fin) =>
                next -> fin
              case Finalized =>
                val fin = next match {
                  case Acquired(_, fin) => fin
                  case _ => F.unit
                }
                Finalized -> (fin *> raise[Unit]("Cannot swap after finalization"))
            }
        }
      }
    }

  /**
   * Creates a [[NonEmptyHotswap]] of `Resource[F, Option[R]]` containing a `None`
   */
  def empty[F[_], R](implicit F: Concurrent[F]): Resource[F, NonEmptyHotswap[F, Option[R]]] =
    apply[F, Option[R]](Resource.pure(none))

  implicit final class NonEmptyHotswapOptionalResourcesOpt[F[_], R](
      private val hs: NonEmptyHotswap[F, Option[R]])
      extends AnyVal {

    /**
     * When the [[cats.effect.kernel.Resource]] contained by a [[NonEmptyHotswap]] is wrapped in
     * an [[scala.Option]] it is not desirable to prevent calls to [[NonEmptyHotswap.swap]] when
     * the [[cats.effect.kernel.Resource Resource]] contains [[scala.None]].
     *
     * [[getOpt]] preserves this behavior from [[Hotswap.get]]
     */
    def getOpt(implicit F: MonadCancel[F, Throwable]): Resource[F, Option[R]] =
      Resource.applyFull[F, Option[R]] { poll =>
        poll(hs.get.allocatedCase).flatMap {
          case (None, fin) => fin(Succeeded) *> F.pure((None, _ => F.unit))
          case (r, fin) => F.pure((r, fin))
        }
      }

    def clear: F[Unit] = hs.swap(Resource.pure(none))
  }
}
