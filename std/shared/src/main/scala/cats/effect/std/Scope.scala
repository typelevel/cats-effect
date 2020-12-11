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

package cats.effect.std

import cats.syntax.all._
import cats.effect.kernel.{Async, Resource}

/**
 * A [[Scope]] represents an effectful region in which resources can be
 * allocated or bound. Whenever this scope exits, all resources which were
 * bound to it are released in the opposite order of allocation.
 *
 * The following example illustrates the ordering of resource lifecycles when
 * bound to a scope:
 *
 * {{{
 *
 *   Scope[IO].use { scope =>
 *     for {
 *       _ <- scope.allocate(Resource.make(IO.println("1"))(_ => IO.println("2")))
 *       _ <- scope.allocate(Resource.make(IO.println("3"))(_ => IO.println("4")))
 *       _ <- IO(println("hello!"))
 *     } yield ()
 *   }
 *
 * }}}
 *
 * Running this program produces the following output:
 *
 * {{{
 *
 *   1
 *   3
 *   hello!
 *   4
 *   2
 *
 * }}}
 */
trait Scope[F[_]] {

  /**
   * Allocate a resource to this scope. The allocated value will be safe to use
   * for as long as this scope remains active.
   */
  def allocate[A](resource: Resource[F, A]): F[A]
}

object Scope {

  /**
   * Create a scope in which resources can be allocated.
   */
  def apply[F[_]](implicit F: Async[F]): Resource[F, Scope[F]] =
    for {
      stateRef <- Resource.make(F.ref[List[F[Unit]]](Nil)) { state =>
        state.get.flatMap(_.sequence).void
      }
    } yield {
      new Scope[F] {
        override def allocate[A](resource: Resource[F, A]): F[A] =
          F.uncancelable { _ =>
            // TODO: interruptible acquire here
            resource.allocated[F, A].flatMap {
              case (a, finalizer) =>
                stateRef.update(finalizer :: _).as(a)
            }
          }
      }
    }

}
