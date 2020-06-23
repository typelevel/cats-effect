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

import scala.concurrent.ExecutionContext

sealed abstract class IO[+A](private[effect] val tag: Int) {

  def map[B](f: A => B): IO[B] = IO.Map(this, f)

  def flatMap[B](f: A => IO[B]): IO[B] = IO.FlatMap(this, f)

  def handleErrorWith[B >: A](f: Throwable => IO[B]): IO[B] =
    IO.HandleErrorWith(this, f)

  def evalOn(ec: ExecutionContext): IO[A] = IO.EvalOn(this, ec)

  def unsafeRunAsync(ec: ExecutionContext)(cb: Either[Throwable, A] => Unit): Unit = {
    val ctxs = new ArrayStack[ExecutionContext](2)
    ctxs.push(ec)

    val conts = new ArrayStack[(Boolean, Any) => Unit](16)   // TODO tune
    conts.push((b, ar) => cb(if (b) Right(ar.asInstanceOf[A]) else Left(ar.asInstanceOf[Throwable])))

    new IOFiber(this, "main").runLoop(this, ctxs, conts)
  }
}

object IO {

  def pure[A](value: A): IO[A] = Pure(value)

  def apply[A](thunk: => A): IO[A] = Delay(() => thunk)

  def raiseError(t: Throwable): IO[Nothing] = Error(t)

  def async[A](k: (Either[Throwable, A] => Unit) => IO[Option[IO[Unit]]]): IO[A] = Async(k)

  val executionContext: IO[ExecutionContext] = IO.ReadEC

  private[effect] final case class Pure[+A](value: A) extends IO[A](0)
  private[effect] final case class Delay[+A](thunk: () => A) extends IO[A](1)
  private[effect] final case class Error(t: Throwable) extends IO[Nothing](2)
  private[effect] final case class Async[+A](k: (Either[Throwable, A] => Unit) => IO[Option[IO[Unit]]]) extends IO[A](3)

  private[effect] case object ReadEC extends IO[ExecutionContext](4)
  private[effect] final case class EvalOn[+A](ioa: IO[A], ec: ExecutionContext) extends IO[A](5)

  private[effect] final case class Map[E, +A](ioe: IO[E], f: E => A) extends IO[A](6)
  private[effect] final case class FlatMap[E, +A](ioe: IO[E], f: E => IO[A]) extends IO[A](7)

  private[effect] final case class HandleErrorWith[+A](ioa: IO[A], f: Throwable => IO[A]) extends IO[A](8)
}
