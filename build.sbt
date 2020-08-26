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

import microsites.{ConfigYml, ExtraMdFileConfig}

import scala.sys.process._
import scala.util.Try
import scala.xml.Elem
import scala.xml.transform.{RewriteRule, RuleTransformer}
import sbtcrossproject.crossProject

ThisBuild / organization := "org.typelevel"
ThisBuild / organizationName := "Typelevel"
ThisBuild / startYear := Some(2017)

val SimulacrumVersion = "1.0.0"
val CatsVersion = "2.2.0-RC2"
val DisciplineScalatestVersion = "2.0.0"
val SilencerVersion = "1.7.1"
val customScalaJSVersion = Option(System.getenv("SCALAJS_VERSION"))

addCommandAlias("ci", ";scalafmtSbtCheck ;scalafmtCheckAll ;test ;mimaReportBinaryIssues; doc")
addCommandAlias("release", ";project root ;reload ;clean ;+publish ;sonatypeReleaseAll ;microsite/publishMicrosite")

val commonSettings = Seq(
  scalacOptions ++= PartialFunction
    .condOpt(CrossVersion.partialVersion(scalaVersion.value)) {
      case Some((2, n)) if n >= 13 =>
        // Necessary for simulacrum
        Seq("-Ymacro-annotations")
    }
    .toList
    .flatten,
  scalacOptions in (Compile, doc) ++= {
    val isSnapshot = git.gitCurrentTags.value.map(git.gitTagToVersionNumber.value).flatten.isEmpty

    val path =
      if (isSnapshot)
        scmInfo.value.get.browseUrl + "/blob/" + git.gitHeadCommit.value.get + "€{FILE_PATH}.scala"
      else
        scmInfo.value.get.browseUrl + "/blob/v" + version.value + "€{FILE_PATH}.scala"

    Seq("-doc-source-url", path, "-sourcepath", baseDirectory.in(LocalRootProject).value.getAbsolutePath)
  },
  sources in (Compile, doc) := (sources in (Compile, doc)).value,
  scalacOptions in (Compile, doc) ++=
    Seq("-doc-root-content", (baseDirectory.value.getParentFile / "shared" / "rootdoc.txt").getAbsolutePath),
  scalacOptions in (Compile, doc) ++=
    Opts.doc.title("cats-effect"),
  scalacOptions in Test += "-Yrangepos",
  scalacOptions in Test ~= (_.filterNot(Set("-Wvalue-discard", "-Ywarn-value-discard"))),
  // Disable parallel execution in tests; otherwise we cannot test System.err
  parallelExecution in Test := false,
  parallelExecution in IntegrationTest := false,
  testForkedParallel in Test := false,
  testForkedParallel in IntegrationTest := false,
  concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),
  logBuffered in Test := false,
  isSnapshot := version.value.endsWith("SNAPSHOT"), // so… sonatype doesn't like git hash snapshots
  publishTo := Some(
    if (isSnapshot.value)
      Opts.resolver.sonatypeSnapshots
    else
      Opts.resolver.sonatypeStaging
  ),
  publishMavenStyle := true,
  pomIncludeRepository := { _ =>
    false
  },
  sonatypeProfileName := organization.value,
  pomExtra :=
    <developers>
      <developer>
        <id>djspiewak</id>
        <name>Daniel Spiewak</name>
        <url>http://www.codecommit.com</url>
      </developer>
      <developer>
        <id>mpilquist</id>
        <name>Michael Pilquist</name>
        <url>https://github.com/mpilquist</url>
      </developer>
      <developer>
        <id>alexelcu</id>
        <name>Alexandru Nedelcu</name>
        <url>https://alexn.org</url>
      </developer>
     <developer>
        <id>SystemFw</id>
        <name>Fabio Labella</name>
        <url>https://github.com/systemfw</url>
      </developer>
    </developers>,
  homepage := Some(url("https://typelevel.org/cats-effect/")),
  scmInfo := Some(ScmInfo(url("https://github.com/typelevel/cats-effect"), "git@github.com:typelevel/cats-effect.git")),
  headerLicense := Some(
    HeaderLicense.Custom(
      """|Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
         |
         |Licensed under the Apache License, Version 2.0 (the "License");
         |you may not use this file except in compliance with the License.
         |You may obtain a copy of the License at
         |
         |    http://www.apache.org/licenses/LICENSE-2.0
         |
         |Unless required by applicable law or agreed to in writing, software
         |distributed under the License is distributed on an "AS IS" BASIS,
         |WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
         |See the License for the specific language governing permissions and
         |limitations under the License.""".stripMargin
    )
  ),
  // For evicting Scoverage out of the generated POM
  // See: https://github.com/scoverage/sbt-scoverage/issues/153
  pomPostProcess := { (node: xml.Node) =>
    new RuleTransformer(new RewriteRule {
      override def transform(node: xml.Node): Seq[xml.Node] = node match {
        case e: Elem
            if e.label == "dependency" && e.child
              .exists(child => child.label == "groupId" && child.text == "org.scoverage") =>
          Nil
        case _ => Seq(node)
      }
    }).transform(node).head
  },
  addCompilerPlugin(("org.typelevel" %% "kind-projector" % "0.11.0").cross(CrossVersion.full)),
  mimaFailOnNoPrevious := false
)

lazy val siteSettings = Seq(
  micrositeName := "Cats Effect",
  micrositeDescription := "The IO Monad for Scala",
  micrositeAuthor := "Cats Effect contributors",
  micrositeGithubOwner := "typelevel",
  micrositeGithubRepo := "cats-effect",
  micrositeBaseUrl := "/cats-effect",
  micrositeTwitterCreator := "@typelevel",
  micrositeDocumentationUrl := "https://typelevel.org/cats-effect/api/",
  micrositeFooterText := None,
  micrositeHighlightTheme := "atom-one-light",
  micrositePalette := Map(
    "brand-primary" -> "#3e5b95",
    "brand-secondary" -> "#294066",
    "brand-tertiary" -> "#2d5799",
    "gray-dark" -> "#49494B",
    "gray" -> "#7B7B7E",
    "gray-light" -> "#E5E5E6",
    "gray-lighter" -> "#F4F3F4",
    "white-color" -> "#FFFFFF"
  ),
  micrositeExtraMdFiles := Map(
    file("README.md") -> ExtraMdFileConfig(
      "index.md",
      "home",
      Map("permalink" -> "/", "title" -> "Home", "section" -> "home", "position" -> "0")
    )
  ),
  micrositeConfigYaml := ConfigYml(
    yamlPath = Some((resourceDirectory in Compile).value / "microsite" / "_config.yml")
  ),
  micrositeCompilingDocsTool := WithMdoc,
  fork in mdoc := true,
  Compile / scalacOptions ~= (_.filterNot(
    Set(
      "-Xfatal-warnings",
      "-Werror",
      "-Ywarn-numeric-widen",
      "-Ywarn-unused:imports",
      "-Ywarn-unused:locals",
      "-Ywarn-unused:patvars",
      "-Ywarn-unused:privates",
      "-Ywarn-numeric-widen",
      "-Ywarn-dead-code",
      "-Xlint:-missing-interpolator,_"
    ).contains
  )),
  docsMappingsAPIDir := "api",
  addMappingsToSiteDir(mappings in packageDoc in Compile in coreJVM, docsMappingsAPIDir)
)

lazy val docs = project
  .in(file("site-docs"))
  .enablePlugins(SiteScaladocPlugin, MdocPlugin, DocusaurusPlugin)
  .settings(commonSettings ++ skipOnPublishSettings ++ sharedSourcesSettings)
  .settings(siteSettings)
  .dependsOn(coreJVM, lawsJVM)

/*
 * Compatibility version. Use this to declare what version with
 * which `master` remains in compatibility. This is literally
 * backwards from how -SNAPSHOT versioning works, but it avoids
 * the need to pre-declare (before work is done) what kind of
 * compatibility properties the next version will have (i.e. major
 * or minor bump).
 *
 * As an example, the builds of a project might go something like
 * this:
 *
 * - 0.1-hash1
 * - 0.1-hash2
 * - 0.1-hash3
 * - 0.1
 * - 0.1-hash1
 * - 0.2-hash2
 * - 0.2
 * - 0.2-hash1
 * - 0.2-hash2
 * - 1.0-hash3
 * - 1.0-hash4
 * - 1.0
 *
 * The value of BaseVersion starts at 0.1 and remains there until
 * compatibility with the 0.1 line is lost, which happens just
 * prior to the release of 0.2. Then the base version again remains
 * 0.2-compatible until that compatibility is broken, with the major
 * version bump of 1.0. Again, this is all to avoid pre-committing
 * to a major/minor bump before the work is done (see: Scala 2.8).
 */
val BaseVersion = "2.0.0"

ThisBuild / licenses += ("Apache-2.0", url("http://www.apache.org/licenses/"))

/***********************************************************************\
                      Boilerplate below these lines
\***********************************************************************/
enablePlugins(GitVersioning)

val ReleaseTag = """^v(\d+\.\d+(?:\.\d+(?:[-.]\w+)?)?)$""".r

git.baseVersion := BaseVersion

git.gitTagToVersionNumber := {
  case ReleaseTag(v) => Some(v)
  case _             => None
}

git.formattedShaVersion := {
  val suffix = git.makeUncommittedSignifierSuffix(git.gitUncommittedChanges.value, git.uncommittedSignifier.value)

  git.gitHeadCommit.value.map(_.substring(0, 7)).map { sha =>
    git.baseVersion.value + "-" + sha + suffix
  }
}

git.gitUncommittedChanges := Try("git status -s".!!.trim.length > 0).getOrElse(true)
git.gitHeadCommit := Try("git rev-parse HEAD".!!.trim).toOption

git.gitCurrentTags := Try("git tag --contains HEAD".!!.trim.split("\\s+").toList).toOption.toList.flatten
