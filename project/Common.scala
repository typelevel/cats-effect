/*
 * Copyright 2020-2022 Typelevel
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

object Common extends AutoPlugin {

  override def requires = plugins.JvmPlugin && TypelevelPlugin
  override def trigger = allRequirements

  override def projectSettings =
    Seq(
      headerLicense := Some(
        HeaderLicense.ALv2(s"${startYear.value.get}-2022", organizationName.value)
      )
    )
}
