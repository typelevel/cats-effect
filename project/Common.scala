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

import sbt._, Keys._

import scalafix.sbt.ScalafixPlugin.autoImport._
import sbtspiewak.SpiewakPlugin, SpiewakPlugin.autoImport._
import sbt.testing.TaskDef

object Common extends AutoPlugin {

  override def requires = plugins.JvmPlugin && SpiewakPlugin
  override def trigger = allRequirements

  override def projectSettings =
    Seq(
      libraryDependencies ++= {
        if (isDotty.value)
          Nil
        else
          Seq(compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"))
      },
      ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0",
      ThisBuild / semanticdbEnabled := !isDotty.value,
      ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
    )
}
