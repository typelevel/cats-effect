/*
 * Copyright 2020-2025 Typelevel
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

import java.util.concurrent.ArrayBlockingQueue

trait IOAppMultiThreaded {
  // arbitrary constant is arbitrary
  private[effect] lazy val queue = new ArrayBlockingQueue[AnyRef](32)

  private[effect] def handleTerminalFailure(t: Throwable): Unit = {
    queue.clear()
    queue.put(t)
  }

  private[effect] def defaultMainThread: ExecutionContext
}
