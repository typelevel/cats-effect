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

package tracing // Get out of the CE package so our traces don't get filtered

import cats.effect.{BaseSpec, DetectPlatform, IO}
import cats.effect.testkit.TestInstances

// Separate from TracingSpec so it can exist outside of cats.effect package
class TraceSpec extends BaseSpec with TestInstances with DetectPlatform { self =>

  "IO" should {
    if (!isJS || !isWSL) {
      "have nice traces" in realWithRuntime { rt =>
        def loop(i: Int): IO[Int] =
          IO.pure(i).flatMap { j =>
            if (j == 0)
              IO.raiseError(new Exception)
            else
              loop(i - 1)
          }
        loop(100).attempt.map {
          case Left(ex) =>
            ex.getStackTrace.count { e =>
              e.getClassName() == s"flatMap @ ${self.getClass().getName()}" && e
                .getMethodName()
                .startsWith("loop$")
            } == rt.config.traceBufferSize
          case _ => false
        }
      }
    } else {
      "have nice traces" in skipped("Scala.js exception unmangling is buggy on WSL")
    }
  }

}
