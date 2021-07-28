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

import org.scalajs.dom.webworkers.Worker
import org.scalajs.dom.window

import scala.concurrent.duration._
import scala.util.Try

class WebWorkerIOSpec extends BaseSpec {

  override def executionTimeout = 5.minutes // This is to run the _entire_ IOSpec

  def scalaVersion = if (BuildInfo.scalaVersion.startsWith("2"))
    BuildInfo.scalaVersion.split("\\.").init.mkString(".")
  else
    BuildInfo.scalaVersion

  def targetDir = s"${BuildInfo.baseDirectory}/target/scala-${scalaVersion}"

  Try(window).toOption.foreach { _ =>
    "io on webworker" should {
      "pass the spec" in real {
        for {
          worker <- IO(
            new Worker(s"file://${targetDir}/cats-effect-webworker-tests-fastopt/main.js"))
          success <- IO.async_[Boolean] { cb =>
            worker.onmessage = { event =>
              event.data match {
                case log: String => println(log)
                case success: Boolean => cb(Right(success))
                case _ => ()
              }
            }
          }
        } yield success mustEqual true
      }
    }
  }

}
