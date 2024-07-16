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

package cats.effect.unsafe

import cats.effect.BaseSpec

class IORuntimeSpec extends BaseSpec {

  "IORuntimeSpec" should {
    "cleanup allRuntimes collection on shutdown" in {
      val (defaultScheduler, closeScheduler) = Scheduler.createDefaultScheduler()

      val runtime = IORuntime(null, null, defaultScheduler, closeScheduler, IORuntimeConfig())

      IORuntime.allRuntimes.unsafeHashtable().find(_ == runtime) must beEqualTo(Some(runtime))

      val _ = runtime.shutdown()

      IORuntime.allRuntimes.unsafeHashtable().find(_ == runtime) must beEqualTo(None)
    }

  }

}
