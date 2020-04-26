/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats.effect.tracing

private[effect] object FiberTracing {

  def get(): Boolean =
    tracingStatus.get()

  def set(newStatus: Boolean): Unit =
    tracingStatus.set(newStatus)

  def reset(): Unit =
    tracingStatus.remove()

  def getAndReset(): Boolean = {
    val s = get()
    reset()
    s
  }

  // TODO: Create a ached global flag so thread-local doesn't
  // have to be read when tracing is disabled

  /**
   * Thread-local storage for fiber tracing status.
   */
  private val tracingStatus = new ThreadLocal[Boolean] {
    override def initialValue(): Boolean = false
  }

}
