/*
 * Copyright 2020 Typelevel
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

/**
 * INTERNAL API — logs uncaught exceptions in a platform specific way.
 *
 * For the JVM logging is accomplished using the current
 * [[https://docs.oracle.com/javase/8/docs/api/java/lang/Thread.UncaughtExceptionHandler.html Thread.UncaughtExceptionHandler]].
 *
 * If an `UncaughtExceptionHandler` is not currently set,
 * then error is printed on standard output.
 */
private[effect] object Logger {

  /** Logs an uncaught error. */
  def reportFailure(e: Throwable): Unit =
    Thread.getDefaultUncaughtExceptionHandler match {
      case null => e.printStackTrace()
      case h    => h.uncaughtException(Thread.currentThread(), e)
    }
}
