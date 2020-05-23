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

sealed abstract private[effect] class TracingMode(val tag: Int)

private[effect] object TracingMode {

  case object Disabled extends TracingMode(0)

  case object Rabbit extends TracingMode(1)

  case object Slug extends TracingMode(2)

  def fromInt(value: Int): TracingMode =
    value match {
      case 1 => Rabbit
      case 2 => Slug
      case _ => Disabled
    }

}
