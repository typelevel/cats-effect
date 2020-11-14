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

package cats.effect.internals

import cats.syntax.all._
import scala.collection.mutable.ListBuffer

private[internals] object NonDaemonThreadLogger {

  /** Whether or not we should check for non-daemon threads on jvm exit */
  def isEnabled(): Boolean =
    Option(System.getProperty("cats.effect.logNonDaemonThreadsOnExit")).map(_.toLowerCase()) match {
      case Some(value) => value.equalsIgnoreCase("true")
      case None        => true // default to enabled
    }

  /** Time to sleep between checking for non-daemon threads present */
  def sleepIntervalMillis: Long =
    Option(System.getProperty("cats.effect.logNonDaemonThreads.sleepIntervalMillis"))
      .flatMap(time => Either.catchOnly[NumberFormatException](time.toLong).toOption)
      .getOrElse(10000L)
}

private[internals] class NonDaemonThreadLogger extends Thread("cats-effect-nondaemon-thread-logger") {

  setDaemon(true)

  override def run(): Unit = {
    var done = false
    while (!done) {
      Thread.sleep(NonDaemonThreadLogger.sleepIntervalMillis)

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
    val msg = threads.mkString("Non-daemon threads currently preventing JVM termination:", "\n - ", "")
    System.err.println(msg)
  }
}
