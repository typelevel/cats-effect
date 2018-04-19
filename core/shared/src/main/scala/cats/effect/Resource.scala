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

import cats._
import cats.implicits._

final class Resource[F[_], A] private (val allocate: F[(A, F[Unit])]) {
  def apply[B](use: A => F[B])(implicit F: Bracket[F, Throwable]): F[B] =
    F.bracket(allocate)(a => use(a._1))(a => a._2)
}

object Resource {
  def apply[F[_]: Functor, A](acquire: F[A])(release: A => F[Unit]): Resource[F, A] =
    new Resource[F, A](acquire.map(a => (a -> release(a))))

  def allocatedBy[F[_], A](allocate: F[(A, F[Unit])]): Resource[F, A] =
    new Resource(allocate)

  implicit def resourceEffect[F[_]](implicit F: Effect[F]): Effect[Resource[F, ?]] = new Effect[Resource[F, ?]] {
    def pure[A](a: A): Resource[F, A] = allocatedBy(F.pure(a -> F.pure(())))
    
    def handleErrorWith[A](fa: Resource[F,A])(f: Throwable => Resource[F,A]): Resource[F,A] =
      allocatedBy(fa.allocate.handleErrorWith(t => f(t).allocate))

    def raiseError[A](e: Throwable): Resource[F,A] =
      allocatedBy(F.raiseError(e))
    
    def async[A](k: (Either[Throwable, A] => Unit) => Unit): Resource[F, A] =
      allocatedBy(F.async(k).map(a => a -> F.pure(())))
    
    def bracketCase[A, B](acquire: Resource[F,A])(use: A => Resource[F,B])(release: (A, ExitCase[Throwable]) => Resource[F,Unit]): Resource[F,B] =
      allocatedBy(acquire.allocate.flatMap { case (a, disposeA) =>
        use(a).allocate.map { case (b, disposeB) =>
          b -> F.bracketCase(disposeB)(F.pure)((_, _) => disposeA)
        }
      })
    
    def runAsync[A](fa: Resource[F,A])(cb: scala.util.Either[Throwable, A] => IO[Unit]): IO[Unit] =
      F.runAsync(fa(F.pure))(cb)
    
    def flatMap[A, B](fa: Resource[F,A])(f: A => Resource[F,B]): Resource[F,B] =
      allocatedBy(F.flatMap(fa.allocate) { case (a, disposeA) =>
        f(a).allocate.map { case (b, disposeB) =>
          b -> F.bracket(disposeB)(F.pure)(_ => disposeA)
        }
      })

    def tailRecM[A, B](a: A)(f: A => Resource[F,Either[A,B]]): Resource[F,B] = ???
    
    def suspend[A](thunk: => Resource[F,A]): Resource[F, A] =
      allocatedBy(F.suspend(thunk.allocate))
  }
}


