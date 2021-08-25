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

package cats.effect
package unsafe

private[unsafe] abstract class IORuntimePlatform { this: IORuntime =>

  private[effect] def suspended(): List[IOFiber[_]] = Nil

  private[effect] def monitor(self: IOFiber[_]): Int = {
    val _ = self
    0
  }

  private[effect] def unmonitor(idx: Int): Unit = {
    val _ = idx
    ()
  }
}
