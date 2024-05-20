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

package cats.effect

import scala.scalajs.js
import scala.util.Try

trait DetectPlatform {

  def isWSL: Boolean = {
    val t = Try {
      val os = js.Dynamic.global.require("os")
      val process = js.Dynamic.global.process

      val isLinux = process.platform.asInstanceOf[String].toLowerCase == "linux"
      val ms = os.release().asInstanceOf[String].toLowerCase.contains("microsoft")

      isLinux && ms // this mis-identifies docker on Windows, which should be considered unsupported for the CE build
    }

    t.getOrElse(false)
  }

  def isJS: Boolean = true
  def isJVM: Boolean = false
  def isNative: Boolean = false
}
