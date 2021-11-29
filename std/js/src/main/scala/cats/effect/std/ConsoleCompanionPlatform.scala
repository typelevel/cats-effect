/*
 * Copyright 2020-2021 Typelevel
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

import cats.effect.kernel.Async
import cats.syntax.all._
import cats.~>

import java.nio.charset.Charset
import scala.annotation.nowarn
import scala.scalajs.js

private[std] trait ConsoleCompanionPlatform { this: Console.type =>

  private[std] abstract class MapKConsole[F[_], G[_]](self: Console[F], f: F ~> G)
      extends Console[G] {
    def readLineWithCharset(charset: Charset): G[String] =
      f(self.readLineWithCharset(charset)): @nowarn("cat=deprecation")
  }

  private[std] final class NodeJSConsole[F[_]](process: js.Dynamic)(implicit F: Async[F])
      extends Console[F] {

    private def write(writable: js.Dynamic, s: String): F[Unit] =
      F.async_[Unit] { cb =>
        writable.write(
          s,
          (e: js.UndefOr[js.Error]) => cb(e.map(js.JavaScriptException(_)).toLeft(())))
        ()
      }

    private def writeln(writable: js.Dynamic, s: String): F[Unit] =
      F.delay(writable.cork()) *> // buffers until uncork
        write(writable, s) *>
        write(writable, "\n") *>
        F.delay(writable.uncork()).void

    def error[A](a: A)(implicit S: cats.Show[A]): F[Unit] = write(process.stderr, S.show(a))

    def errorln[A](a: A)(implicit S: cats.Show[A]): F[Unit] = writeln(process.stderr, S.show(a))

    def print[A](a: A)(implicit S: cats.Show[A]): F[Unit] = write(process.stdout, S.show(a))

    def println[A](a: A)(implicit S: cats.Show[A]): F[Unit] = writeln(process.stdout, S.show(a))

    def readLineWithCharset(charset: Charset): F[String] = ???
  }

}
