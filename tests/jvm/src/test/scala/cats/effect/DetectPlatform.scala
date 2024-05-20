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

trait DetectPlatform {
  def isWSL: Boolean = System.getProperty("os.version").contains("-WSL")
  def isJS: Boolean = false
  def isJVM: Boolean = true
  def isNative: Boolean = false

  def javaMajorVersion: Int =
    System.getProperty("java.version").stripPrefix("1.").takeWhile(_.isDigit).toInt
}
