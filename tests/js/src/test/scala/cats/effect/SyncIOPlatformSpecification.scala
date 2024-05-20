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

trait SyncIOPlatformSpecification { self: BaseSpec =>
  def platformSpecs = {
    "platform" should {
      "realTimeDate should return an Instant constructed from realTime" in {
        // Unfortunately since SyncIO doesn't rely on a controllable
        // time source, this is the best I can do
        val op = for {
          realTime <- SyncIO.realTime
          jsDate <- SyncIO.realTimeDate
        } yield (jsDate.getTime().toLong - realTime.toMillis) <= 30

        op must completeAsSync(true)
      }
    }
  }

}
