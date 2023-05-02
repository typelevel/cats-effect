/*
 * Copyright 2020-2023 Typelevel
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

class IORuntimeBuilderSpec extends BaseSpec with DetectPlatform {

  "IORuntimeBuilder" should {
    if (isNative) "configure the failure reporter" in pending
    else
      "configure the failure reporter" in {
        var invoked = false
        val rt = IORuntime.builder().setFailureReporter(_ => invoked = true).build()
        rt.compute.reportFailure(new Exception)
        invoked must beTrue
      }
  }

}
