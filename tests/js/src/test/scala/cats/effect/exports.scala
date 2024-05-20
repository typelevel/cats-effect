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
import scala.scalajs.js.annotation._

// we don't care about this object, but we want to run its initializer
@JSExportTopLevel("dummy")
object exports extends js.Object {
  if (js.typeOf(js.Dynamic.global.process) == "undefined") {
    js.special.fileLevelThis.asInstanceOf[js.Dynamic].process = js.Object()
    if (js.typeOf(js.Dynamic.global.process.env) == "undefined")
      js.Dynamic.global.process.env = js.Object()
    js.Dynamic.global.process.env.CATS_EFFECT_TRACING_MODE = "cached"
  }
}
