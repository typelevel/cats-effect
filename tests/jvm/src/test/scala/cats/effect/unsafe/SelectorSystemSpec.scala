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
package unsafe

class SelectorSystemSpec extends BaseSpec {

  "SelectorSystem" should {
    "not blocker stealer when owner is polling" in real {
      IO(SelectorSystem()).flatMap { s =>
        IO(s.makePoller()).flatMap { p =>
          IO.interruptible(s.poll(p, -1, _ => ())).background.surround {
            IO(s.steal(p, _ => ()) should beFalse)
          }
        }
      }
    }
  }

}
