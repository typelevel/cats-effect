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

import cats.effect.kernel.{Async, Sync}
import cats.syntax.all._

import java.nio.charset.Charset
import java.util.concurrent.LinkedTransferQueue

private[std] trait ConsoleCompanionPlatform extends ConsoleCompanionCrossPlatform {

  /**
   * Constructs a `Console` instance for `F` data types that are [[cats.effect.kernel.Async]].
   */
  def make[F[_]](implicit F: Async[F]): Console[F] =
    new AsyncConsole[F]

  @deprecated("Use overload with Async constraint", "3.5.0")
  def make[F[_]](F: Sync[F]): Console[F] = F match {
    case async: Async[F] => make(async)
    case _ => new SyncConsole()(F)
  }

  private final class AsyncConsole[F[_]](implicit F: Async[F]) extends SyncConsole[F] {
    override def readLineWithCharset(charset: Charset): F[String] =
      F.async[String] { cb =>
        F.delay(stdinReader.readLineWithCharset(charset, cb)).map { cancel =>
          Some(F.delay(cancel.run()))
        }
      }
  }

  private object stdinReader extends Thread {

    private final class ReadLineRequest(
        val charset: Charset,
        @volatile var callback: Either[Throwable, String] => Unit
    ) extends Runnable {
      def run() = callback = null
    }

    private[this] val requests = new LinkedTransferQueue[ReadLineRequest]

    def readLineWithCharset(
        charset: Charset,
        cb: Either[Throwable, String] => Unit): Runnable = {
      val request = new ReadLineRequest(charset, cb)
      requests.offer(request)
      request
    }

    setName("cats-effect-stdin")
    setDaemon(true)
    start()

    override def run(): Unit = {
      var request: ReadLineRequest = null
      var charset: Charset = null
      var line: Either[Throwable, String] = null

      while (true) {
        // wait for a non-canceled request. store callback b/c it is volatile read
        var callback: Either[Throwable, String] => Unit = null
        while ((request eq null) || { callback = request.callback; callback eq null })
          request = requests.take()

        if (line eq null) { // need a line for current request
          charset = request.charset // remember the charset we used
          line = Either.catchNonFatal(readLineWithCharsetImpl(charset))
          // we just blocked, so loop to possibly freshen current request
        } else { // we have a request and a line!
          if (request.charset != charset) { // not the charset we have :/
            callback(Left(new IllegalStateException(s"Next read must be for $charset line")))
            request = null // loop to get a new request that can handle this charset
          } else { // happy days!
            callback(line)
            // reset our state
            request = null
            charset = null
            line = null
          }
        }

      }
    }
  }

}
