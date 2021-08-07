/*
 * Copyright 2020-2021 Typelevel
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

import cats.effect.kernel.Cont

import scala.scalajs.js
import scala.util.Try

private[tracing] abstract class TracingPlatform { self: Tracing.type =>

  private val cache = js.Dictionary.empty[TracingEvent]

  import TracingConstants._

  def calculateTracingEvent[A](f: Function0[A]): TracingEvent = {
    calculateTracingEvent(f.asInstanceOf[scalajs.js.Dynamic].sjsr_AnonFunction0__f_f.toString())
  }

  def calculateTracingEvent[A, B](f: Function1[A, B]): TracingEvent = {
    calculateTracingEvent(f.asInstanceOf[scalajs.js.Dynamic].sjsr_AnonFunction1__f_f.toString())
  }

  // We could have a catch-all for non-functions, but explicitly enumerating makes sure we handle each case correctly
  def calculateTracingEvent[F[_], A, B](cont: Cont[F, A, B]): TracingEvent = {
    calculateTracingEvent(cont.getClass().getName())
  }

  private def calculateTracingEvent(key: String): TracingEvent = {
    if (isCachedStackTracing) {
      Try(cache(key)).recover {
        case _ =>
          val event = buildEvent()
          cache(key) = event
          event
      }.get
    } else if (isFullStackTracing) {
      buildEvent()
    } else {
      null
    }
  }

}
