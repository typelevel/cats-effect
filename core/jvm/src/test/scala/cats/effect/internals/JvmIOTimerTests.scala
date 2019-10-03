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

package cats.effect
package internals

import java.util.concurrent.{ScheduledThreadPoolExecutor, TimeUnit}

import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AnyFunSuite

import scala.util.control.NonFatal

class JvmIOTimerTests extends AnyFunSuite with Matchers {
  private def withScheduler(props: Map[String, String])(f: ScheduledThreadPoolExecutor => Unit): Unit = {
    val s = IOTimer.mkGlobalScheduler(props)
    try f(s)
    finally {
      try s.shutdownNow()
      catch { case NonFatal(e) => e.printStackTrace() }
    }
  }

  test("global scheduler: default core pool size") {
    withScheduler(Map.empty) { s =>
      s.getCorePoolSize shouldBe 2
    }
  }

  test("global scheduler: custom core pool size") {
    withScheduler(Map("cats.effect.global_scheduler.threads.core_pool_size" -> "3")) { s =>
      s.getCorePoolSize shouldBe 3
    }
  }

  test("global scheduler: invalid core pool size") {
    withScheduler(Map("cats.effect.global_scheduler.threads.core_pool_size" -> "-1")) { s =>
      s.getCorePoolSize shouldBe 2
    }
  }

  test("global scheduler: malformed core pool size") {
    withScheduler(Map("cats.effect.global_scheduler.threads.core_pool_size" -> "banana")) { s =>
      s.getCorePoolSize shouldBe 2
    }
  }

  test("global scheduler: default core thread timeout") {
    withScheduler(Map.empty) { s =>
      s.allowsCoreThreadTimeOut shouldBe false
    }
  }

  test("global scheduler: custom core thread timeout") {
    withScheduler(Map("cats.effect.global_scheduler.keep_alive_time_ms" -> "1000")) { s =>
      s.allowsCoreThreadTimeOut shouldBe true
      s.getKeepAliveTime(TimeUnit.MILLISECONDS) shouldBe 1000
    }
  }

  test("global scheduler: invalid core thread timeout") {
    withScheduler(Map("cats.effect.global_scheduler.keep_alive_time_ms" -> "0")) { s =>
      s.allowsCoreThreadTimeOut shouldBe false
    }
  }

  test("global scheduler: malformed core thread timeout") {
    withScheduler(Map("cats.effect.global_scheduler.keep_alive_time_ms" -> "feral hogs")) { s =>
      s.allowsCoreThreadTimeOut shouldBe false
    }
  }
}
