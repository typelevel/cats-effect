/*
 * Copyright 2017 Typelevel
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

package cats.effect.internals

import java.util.concurrent.atomic.AtomicBoolean
import cats.effect.internals.TrampolineEC.{immediate => ec}

private[effect] final class SafeCallback[-A](
  conn: Connection,
  cb: Either[Throwable, A] => Unit)
  extends (Either[Throwable, A] => Unit) {

  private[this] val canCall = new AtomicBoolean(true)

  def apply(value: Either[Throwable, A]): Unit = {
    if (canCall.getAndSet(false)) {
      ec.execute(new Runnable {
        def run(): Unit = {
          if (conn ne null) conn.pop()
          cb(value)
        }
      })
    } else value match {
      case Right(_) => ()
      case Left(e) => ec.reportFailure(e)
    }
  }
}
