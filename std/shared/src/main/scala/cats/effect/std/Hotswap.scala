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

package cats.effect.std

import cats.effect.kernel.{Concurrent, Ref, Resource}
import cats.effect.kernel.syntax.all._
import cats.syntax.all._

/**
 * A concurrent data structure that exposes a linear sequence of `R` resources as a single
 * [[cats.effect.kernel.Resource]] in `F` without accumulation.
 *
 * A [[Hotswap]] is allocated within a [[cats.effect.kernel.Resource]] that dictates the scope
 * of its lifetime. After creation, a `Resource[F, R]` can be swapped in by calling [[swap]].
 * The newly acquired resource is returned and is released either when the [[Hotswap]] is
 * finalized or upon the next call to [[swap]], whichever occurs first.
 *
 * The following diagram illustrates the linear allocation and release of three resources `r1`,
 * `r2`, and `r3` cycled through [[Hotswap]]:
 *
 * {{{
 * >----- swap(r1) ---- swap(r2) ---- swap(r3) ----X
 * |        |             |             |          |
 * Creation |             |             |          |
 *         r1 acquired    |             |          |
 *                       r2 acquired    |          |
 *                       r1 released   r3 acquired |
 *                                     r2 released |
 *                                                 r3 released
 * }}}
 *
 * [[Hotswap]] is particularly useful when working with effects that cycle through resources,
 * like writing bytes to files or rotating files every N bytes or M seconds. Without
 * [[Hotswap]], such effects leak resources: on each file rotation, a file handle or some
 * internal resource handle accumulates. With [[Hotswap]], the only registered resource is the
 * [[Hotswap]] itself, and each file is swapped in only after swapping the previous one out.
 *
 * Ported from https://github.com/typelevel/fs2.
 */
sealed trait Hotswap[F[_], R] {

  /**
   * Allocates a new resource, closes the previous one if it exists, and returns the newly
   * allocated `R`.
   *
   * When the lifetime of the [[Hotswap]] is completed, the resource allocated by the most
   * recent [[swap]] will be finalized.
   *
   * [[swap]] finalizes the previous resource immediately, so users must ensure that the old `R`
   * is not used thereafter. Failure to do so may result in an error on the _consumer_ side. In
   * any case, no resources will be leaked.
   *
   * For safer access to the current resource see [[get]], which guarantees that it will not be
   * released while it is being used.
   *
   * If [[swap]] is called after the lifetime of the [[Hotswap]] is over, it will raise an
   * error, but will ensure that all resources are finalized before returning.
   */
  def swap(next: Resource[F, R]): F[R]

  /**
   * Gets the current resource, if it exists. The returned resource is guaranteed to be
   * available for the duration of the returned resource.
   */
  def get: Resource[F, Option[R]]

  /**
   * Pops and runs the finalizer of the current resource, if it exists.
   *
   * Like [[swap]], users must ensure that the old `R` is not used after calling [[clear]].
   * Calling [[clear]] after the lifetime of this [[Hotswap]] results in an error.
   */
  def clear: F[Unit]

}

object Hotswap {

  /**
   * Creates a new [[Hotswap]] initialized with the specified resource. The [[Hotswap]] instance
   * and the initial resource are returned.
   */
  def apply[F[_]: Concurrent, R](initial: Resource[F, R]): Resource[F, (Hotswap[F, R], R)] =
    create[F, R].evalMap(hotswap => hotswap.swap(initial).tupleLeft(hotswap))

  /**
   * Creates a new [[Hotswap]], which represents a [[cats.effect.kernel.Resource]] that can be
   * swapped during the lifetime of this [[Hotswap]].
   */
  def create[F[_], R](implicit F: Concurrent[F]): Resource[F, Hotswap[F, R]] =
    Resource.eval(Semaphore[F](Long.MaxValue)).flatMap { semaphore =>
      sealed abstract class State
      case object Cleared extends State
      case class Acquired(r: R, fin: F[Unit]) extends State
      case object Finalized extends State

      def initialize: F[Ref[F, State]] =
        F.ref(Cleared)

      def finalize(state: Ref[F, State]): F[Unit] =
        state.getAndSet(Finalized).flatMap {
          case Acquired(_, finalizer) => exclusive.surround(finalizer)
          case Cleared => F.unit
          case Finalized => raise("Hotswap already finalized")
        }

      def raise(message: String): F[Unit] =
        F.raiseError[Unit](new RuntimeException(message))

      def exclusive: Resource[F, Unit] =
        Resource.makeFull[F, Unit](poll => poll(semaphore.acquireN(Long.MaxValue)))(_ =>
          semaphore.releaseN(Long.MaxValue))

      Resource.make(initialize)(finalize).map { state =>
        new Hotswap[F, R] {

          override def swap(next: Resource[F, R]): F[R] =
            F.uncancelable { poll =>
              poll(next.allocated).flatMap {
                case (r, fin) =>
                  exclusive.mapK(poll).onCancel(Resource.eval(fin)).surround {
                    swapFinalizer(Acquired(r, fin)).as(r)
                  }
              }
            }

          override def get: Resource[F, Option[R]] =
            Resource.makeFull[F, Option[R]] { poll =>
              poll(semaphore.acquire) *> // acquire shared lock
                state.get.flatMap {
                  case Acquired(r, _) => F.pure(Some(r))
                  case _ => semaphore.release.as(None)
                }
            } { r => if (r.isDefined) semaphore.release else F.unit }

          override def clear: F[Unit] =
            exclusive.surround(swapFinalizer(Cleared).uncancelable)

          private def swapFinalizer(next: State): F[Unit] =
            state.modify {
              case Acquired(_, fin) =>
                next -> fin
              case Cleared =>
                next -> F.unit
              case Finalized =>
                val fin = next match {
                  case Acquired(_, fin) => fin
                  case _ => F.unit
                }
                Finalized -> (fin *> raise("Cannot swap after finalization"))
            }.flatten

        }
      }
    }

}
