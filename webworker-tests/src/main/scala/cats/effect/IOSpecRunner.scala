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

package cats.effect

import org.scalajs.dom.webworkers.DedicatedWorkerGlobalScope
import org.specs2.runner.ClassRunner
import org.specs2.specification.core.Env
import org.specs2.runner.Runner
import org.specs2.control.ExecuteActions
import org.specs2.reporter.LineLogger

object IOSpecRunner extends IOApp.Simple with ClassRunner {

  override def run: IO[Unit] = IO.fromFuture {
    IO {
      val spec = new IOSpec
      val env = Env(lineLogger = LineLogger.consoleLogger)
      val loader = new ClassLoader() {}
      val action = for {
        printers <- createPrinters(env.arguments, loader).toAction
        stats <- Runner.runSpecStructure(spec.structure(env), env, loader, printers)
        // TODO I have no idea how to suspend effects in this
        _ = DedicatedWorkerGlobalScope.self.postMessage(stats.isSuccess)
      } yield ()
      ExecuteActions.runActionFuture(action)(env.executionEnv)
    }
  }

}
