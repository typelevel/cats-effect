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

import scala.scalajs.js.{|, defined, JavaScriptException, Promise, Thenable}

private[effect] abstract class IOCompanionPlatform { self: IO.type =>

  def fromPromise[A](iop: IO[Promise[A]]): IO[A] =
    iop flatMap { p =>
      IO.async_[A] { cb =>
        p.`then`[Unit](
          (v: A) => cb(Right(v)): Unit | Thenable[Unit],

          defined { (a: Any) =>
            val e = a match {
              case th: Throwable => th
              case _ => JavaScriptException(a)
            }

            cb(Left(e)): Unit | Thenable[Unit]
          })
      }
    }
}
