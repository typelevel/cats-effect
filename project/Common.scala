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

import sbt._, Keys._

import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport._
import org.typelevel.sbt.TypelevelPlugin
import org.typelevel.sbt.TypelevelKernelPlugin.autoImport._
import org.typelevel.sbt.TypelevelMimaPlugin.autoImport._
import scalafix.sbt.ScalafixPlugin, ScalafixPlugin.autoImport._
import sbtcrossproject.CrossPlugin.autoImport._
import scalanativecrossproject.NativePlatform

object Common extends AutoPlugin {

  override def requires = plugins.JvmPlugin && TypelevelPlugin && ScalafixPlugin
  override def trigger = allRequirements

  override def buildSettings =
    Seq(
      semanticdbEnabled := true,
      semanticdbVersion := scalafixSemanticdb.revision
    )

  override def projectSettings =
    Seq(
      headerLicense := Some(
        HeaderLicense.ALv2(s"${startYear.value.get}-2024", organizationName.value)
      ),
      tlVersionIntroduced ++= {
        if (crossProjectPlatform.?.value.contains(NativePlatform))
          List("2.12", "2.13", "3").map(_ -> "3.4.0").toMap
        else
          Map.empty
      }
    )
}
