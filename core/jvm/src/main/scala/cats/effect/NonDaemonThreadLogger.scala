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

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration

private[effect] class NonDaemonThreadLogger(interval: FiniteDuration)
    extends Thread("cats-effect-nondaemon-thread-logger") {

  setDaemon(true)

  override def run(): Unit = {
    var done = false
    while (!done) {
      Thread.sleep(interval.toMillis)

      val runningThreads = detectThreads()
      if (runningThreads.isEmpty) {
        // This is probably impossible, as we are supposed to be a *daemon* thread, so the jvm should exit by itself
        done = true
      } else {
        printThreads(runningThreads)
      }
    }
  }

  private[this] def detectThreads(): List[String] = {
    val threads = Thread.getAllStackTraces().keySet()
    val nonDaemons = ListBuffer[String]()
    threads.forEach { t =>
      if (!t.isDaemon)
        nonDaemons += s" - ${t.getId}: ${t}"
    }
    nonDaemons.toList
  }

  private[this] def printThreads(threads: List[String]) = {
    val msg =
      threads.mkString("Non-daemon threads currently preventing JVM termination:", "\n - ", "")
    System.err.println(msg)
  }
}
