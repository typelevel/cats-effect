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

trait IOAppCommon {
  this: IOApp =>

  private[this] var _runtime: unsafe.IORuntime = null

  private[effect] def installedRuntime: unsafe.IORuntime = _runtime

  private[effect] def setupGlobalRuntime(): Unit = {
    val installed = if (runtime == null) {
      import unsafe.IORuntime

      val installed = IORuntime installGlobal defaultGlobalRuntime

      _runtime = IORuntime.global

      installed
    } else {
      unsafe.IORuntime.installGlobal(runtime)
    }

    if (!installed) {
      System
        .err
        .println(
          "WARNING: Cats Effect global runtime already initialized; custom configurations will be ignored")
    }
  }
}
