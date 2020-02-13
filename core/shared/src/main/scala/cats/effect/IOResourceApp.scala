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

package cats
package effect

import cats.Eval

import cats.effect.internals.{IOAppPlatform, IOResourceAppPlatform}

trait IOResourceApp {
  /**
   * Produces the `Resource[IO, ExitCode]` to be run as an app.
   *
   * @return the [[cats.effect.ExitCode]] the JVM exits with
   */
  def run(args: List[String]): Resource[IO, ExitCode]

  /**
   * The main method that runs the `IO` returned by [[run]] and exits
   * the app with the resulting code on completion.
   */
  def main(args: Array[String]): Unit =
    IOResourceAppPlatform.main(args, Eval.later(contextShift), Eval.later(timer))(run)

  /**
   * Provides an implicit [[ContextShift]] for the app.
   *
   * The default on top of the JVM is lazily constructed as a fixed
   * thread pool based on number available of available CPUs (see
   * `PoolUtils`).
   *
   * On top of JavaScript, the global execution context is used
   * (i.e. `scala.concurrent.ExecutionContext.Implicits.global`).
   *
   * Users can override this value in order to customize the main
   * thread-pool on top of the JVM, or to customize the run-loop on
   * top of JavaScript.
   */
  implicit protected def contextShift: ContextShift[IO] =
    IOAppPlatform.defaultContextShift

  /**
   * Provides an implicit [[Timer]] for the app.
   *
   * Users can override this value in order to customize the
   * underlying scheduler being used.
   *
   * The default on top of the JVM uses an internal scheduler built with Java's
   * [[https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/Executors.html#newScheduledThreadPool-int- Executors.newScheduledThreadPool]]
   * (configured with one or two threads) and that defers the execution of the
   * scheduled ticks (the bind continuations get shifted) to the app's [[contextShift]].
   *
   * On top of JavaScript the default timer will simply use the standard
   * [[https://developer.mozilla.org/en-US/docs/Web/API/WindowOrWorkerGlobalScope/setTimeout setTimeout]].
   */
  implicit protected def timer: Timer[IO] =
    IOAppPlatform.defaultTimer
}
