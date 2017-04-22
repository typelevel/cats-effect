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

package cats
package effect

import simulacrum._

import scala.concurrent.ExecutionContext
import scala.util.Either

@typeclass
trait Effect[F[_]] extends Sync[F] with Async[F] with LiftIO[F] {

  def runAsync[A](fa: F[A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit]

  /**
   * @see IO#shift
   */
  def shift[A](fa: F[A])(implicit ec: ExecutionContext): F[A] = {
    val self = flatMap(attempt(fa)) { e =>
      async { (cb: Either[Throwable, A] => Unit) =>
        ec.execute(new Runnable {
          def run() = cb(e)
        })
      }
    }

    async { cb =>
      ec.execute(new Runnable {
        def run() = runAsync(self)(e => IO { cb(e) }).unsafeRunSync()
      })
    }
  }
}
