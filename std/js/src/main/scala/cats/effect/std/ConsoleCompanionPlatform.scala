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

import cats.Show
import cats.syntax.all._
import cats.effect.kernel.Async

import java.nio.charset.{Charset, CodingErrorAction, MalformedInputException}
import scala.scalajs.js

private[std] trait ConsoleCompanionPlatform { this: Console.type =>

  final class NodeJSConsole[F[_]](out: js.Dynamic, err: js.Dynamic, in: js.Dynamic)(
      implicit F: Async[F])
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
        F.delay(writable.uncork().asInstanceOf[Unit])

    def error[A](a: A)(implicit S: Show[A]): F[Unit] = write(err, S.show(a))

    def errorln[A](a: A)(implicit S: Show[A]): F[Unit] = writeln(err, S.show(a))

    def print[A](a: A)(implicit S: Show[A]): F[Unit] = write(out, S.show(a))

    def println[A](a: A)(implicit S: Show[A]): F[Unit] = writeln(out, S.show(a))

    def readLineWithCharset(charset: Charset): F[String] = {
      val decoder = charset
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
      F.async_[String] { cb =>
        var onceData: js.Function1[js.typedarray.Uint8Array, Unit] = null
        var onceError: js.Function1[js.Error, Unit] = null
        onceData = { buffer =>
          val int8buffer =
            new js.typedarray.Int8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength)
          val chars = Either.catchNonFatal(decoder.decode(js.typedarray.TypedArrayBuffer.wrap(int8buffer)))
          in.off("error", onceError)
          ()
        }
        onceError = { e =>
          cb(Left(js.JavaScriptException(e)))
          in.off("data", onceData)
          ()
        }
        ???
      }

    }
  }

}
