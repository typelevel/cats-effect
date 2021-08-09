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

sealed abstract class CI(
    rootProject: String,
    jsEnv: Option[JSEnv],
    testCommands: List[String],
    mimaReport: Boolean,
    suffixCommands: List[String]) {

  override val toString: String = {
    val commands =
      (List(
        s"project $rootProject",
        jsEnv.fold("")(env => s"set Global / useJSEnv := JSEnv.$env"),
        "headerCheck",
        "scalafmtSbtCheck",
        "scalafmtCheck",
        "clean"
      ) ++ testCommands ++ List(
        jsEnv.fold("")(_ => s"set Global / useJSEnv := JSEnv.NodeJS"),
        if (mimaReport) "mimaReportBinaryIssues" else ""
      )).filter(_.nonEmpty) ++ suffixCommands

    commands.mkString("; ", "; ", "")
  }
}

object CI {
  case object JVM
      extends CI(
        rootProject = "rootJVM",
        jsEnv = None,
        testCommands = List("test"),
        mimaReport = true,
        suffixCommands = List("root/unidoc213", "exampleJVM/compile"))

  case object JS
      extends CI(
        rootProject = "rootJS",
        jsEnv = Some(JSEnv.NodeJS),
        testCommands = List("test"),
        mimaReport = false,
        suffixCommands = List("exampleJS/compile"))

  case object Firefox
      extends CI(
        rootProject = "rootJS",
        jsEnv = Some(JSEnv.Firefox),
        testCommands = List("testsJS/test", "webWorkerTests/test"),
        mimaReport = false,
        suffixCommands = List())

  case object Chrome
      extends CI(
        rootProject = "rootJS",
        jsEnv = Some(JSEnv.Chrome),
        testCommands = List("testsJS/test", "webWorkerTests/test"),
        mimaReport = false,
        suffixCommands = List())

  case object JSDOMNodeJS
      extends CI(
        rootProject = "rootJS",
        jsEnv = Some(JSEnv.JSDOMNodeJS),
        testCommands = List("testsJS/test"),
        mimaReport = false,
        suffixCommands = List())

  val AllCIs: List[CI] = List(JVM, JS, Firefox, Chrome, JSDOMNodeJS)
}
