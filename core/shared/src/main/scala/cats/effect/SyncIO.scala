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

package cats
package effect

final class SyncIO[+A] private (val toIO: IO[A]) {
  def unsafeRunSync(): A = toIO.unsafeRunSync

  def attempt: SyncIO[Either[Throwable, A]] = new SyncIO(toIO.attempt)
  def map[B](f: A => B): SyncIO[B] = new SyncIO(toIO.map(f))
  def flatMap[B](f: A => SyncIO[B]): SyncIO[B] = new SyncIO(toIO.flatMap(a => f(a).toIO))
}

object SyncIO {
  def pure[A](a: A): SyncIO[A] = new SyncIO(IO.pure(a))
  def apply[A](thunk: => A): SyncIO[A] = new SyncIO(IO(thunk))
  def suspend[A](thunk: => SyncIO[A]): SyncIO[A] = new SyncIO(IO.suspend(thunk.toIO))
  val unit: SyncIO[Unit] = pure(())
  def eval[A](fa: Eval[A]): SyncIO[A] = fa match {
    case Now(a) => pure(a)
    case notNow => apply(notNow.value)
  }
  def raiseError[A](e: Throwable): SyncIO[A] = new SyncIO(IO.raiseError(e))
  def fromEither[A](e: Either[Throwable, A]): SyncIO[A] = new SyncIO(IO.fromEither(e))
}