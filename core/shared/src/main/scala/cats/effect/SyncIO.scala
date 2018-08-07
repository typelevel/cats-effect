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

import scala.annotation.unchecked.uncheckedVariance

final class SyncIO[+A] private (val toIO: IO[A]) {
  def unsafeRunSync(): A = toIO.unsafeRunSync

  def map[B](f: A => B): SyncIO[B] = new SyncIO(toIO.map(f))
  def flatMap[B](f: A => SyncIO[B]): SyncIO[B] = new SyncIO(toIO.flatMap(a => f(a).toIO))
  def attempt: SyncIO[Either[Throwable, A]] = new SyncIO(toIO.attempt)

  /**
   * Converts the source `IO` into any `F` type that implements
   * the [[LiftIO]] type class.
   */
  final def to[F[_]](implicit F: LiftIO[F]): F[A @uncheckedVariance] =
    F.liftIO(toIO)

  final def bracket[B](use: A => SyncIO[B])(release: A => SyncIO[Unit]): SyncIO[B] =
    bracketCase(use)((a, _) => release(a))

  def bracketCase[B](use: A => SyncIO[B])(release: (A, ExitCase[Throwable]) => SyncIO[Unit]): SyncIO[B] =
    new SyncIO(toIO.bracketCase(a => use(a).toIO)((a, ec) => release(a, ec).toIO))

  def guarantee(finalizer: SyncIO[Unit]): SyncIO[A] =
    guaranteeCase(_ => finalizer)

  def guaranteeCase(finalizer: ExitCase[Throwable] => SyncIO[Unit]): SyncIO[A] =
    new SyncIO(toIO.guaranteeCase(ec => finalizer(ec).toIO))

  def handleErrorWith[AA >: A](f: Throwable => SyncIO[AA]): SyncIO[AA] =
    new SyncIO(toIO.handleErrorWith(t => f(t).toIO))

  def redeem[B](recover: Throwable => B, map: A => B): SyncIO[B] =
    new SyncIO(toIO.redeem(recover, map))

  def redeemWith[B](recover: Throwable => SyncIO[B], bind: A => SyncIO[B]): SyncIO[B] =
    new SyncIO(toIO.redeemWith(t => recover(t).toIO, a => bind(a).toIO))

  override def toString: String = toIO match {
    case IO.Pure(a) => s"SyncIO($a)"
    case IO.RaiseError(e) => s"SyncIO(throw $e)"
    case _ => "SyncIO$" + System.identityHashCode(this)
  }
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