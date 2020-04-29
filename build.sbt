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
import scala.xml.Elem
import scala.xml.transform.{RewriteRule, RuleTransformer}
import sbtcrossproject.crossProject

ThisBuild / organization := "org.typelevel"
ThisBuild / organizationName := "Typelevel"
ThisBuild / startYear := Some(2017)

val CompileTime = config("CompileTime").hide
val SimulacrumVersion = "1.0.0"
val CatsVersion = "2.1.1"
val DisciplineScalatestVersion = "1.0.1"
val SilencerVersion = "1.7.0"
val customScalaJSVersion = Option(System.getenv("SCALAJS_VERSION"))

addCommandAlias("ci", ";scalafmtSbtCheck ;scalafmtCheckAll ;test ;mimaReportBinaryIssues; doc")

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
  // credit: https://github.com/typelevel/cats/pull/1638
  ivyConfigurations += CompileTime,
  unmanagedClasspath in Compile ++= update.value.select(configurationFilter("CompileTime")),
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

val mimaSettings = Seq(
  mimaPreviousArtifacts := {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 13)) => Set(organization.value %% name.value % "2.0.0")
      case Some((2, 12)) => Set(organization.value %% name.value % "1.0.0")
      case _             => Set.empty
    }
  },
  mimaBinaryIssueFilters ++= {
    import com.typesafe.tools.mima.core._
    import com.typesafe.tools.mima.core.ProblemFilters._
    Seq(
      // Ignore any binary compatibility issues/problems that match the internals package
      exclude[Problem]("cats.effect.internals.*"),
      // All internals - https://github.com/typelevel/cats-effect/pull/403
      exclude[DirectMissingMethodProblem]("cats.effect.concurrent.Semaphore#AbstractSemaphore.awaitGate"),
      exclude[DirectMissingMethodProblem]("cats.effect.concurrent.Semaphore#AsyncSemaphore.awaitGate"),
      exclude[DirectMissingMethodProblem]("cats.effect.concurrent.Semaphore#ConcurrentSemaphore.awaitGate"),
      // All internals — https://github.com/typelevel/cats-effect/pull/424
      exclude[MissingClassProblem]("cats.effect.concurrent.Deferred$UncancelabbleDeferred"),
      // Laws - https://github.com/typelevel/cats-effect/pull/473
      exclude[ReversedMissingMethodProblem]("cats.effect.laws.AsyncLaws.repeatedAsyncFEvaluationNotMemoized"),
      exclude[ReversedMissingMethodProblem]("cats.effect.laws.BracketLaws.bracketPropagatesTransformerEffects"),
      exclude[ReversedMissingMethodProblem]("cats.effect.laws.discipline.BracketTests.bracketTrans"),
      // Static forwarder not generated. We tried. - https://github.com/typelevel/cats-effect/pull/584
      exclude[DirectMissingMethodProblem]("cats.effect.IO.fromFuture"),
      // Incompatible signatures should not cause linking problems.
      exclude[IncompatibleSignatureProblem]("cats.effect.IO.ioParallel"),
      exclude[IncompatibleSignatureProblem]("cats.effect.IOInstances.ioParallel"),
      // Signature changes to make Resource covariant, should not cause linking problems. - https://github.com/typelevel/cats-effect/pull/731
      exclude[IncompatibleSignatureProblem]("cats.effect.Resource.use"),
      exclude[IncompatibleSignatureProblem]("cats.effect.Resource.flatMap"),
      exclude[IncompatibleSignatureProblem]("cats.effect.Resource.map"),
      exclude[IncompatibleSignatureProblem]("cats.effect.Resource.mapK"),
      exclude[IncompatibleSignatureProblem]("cats.effect.Resource.allocated"),
      exclude[IncompatibleSignatureProblem]("cats.effect.Resource.evalMap"),
      exclude[IncompatibleSignatureProblem]("cats.effect.Resource.evalTap"),
      // change in encoding of value classes in generic methods https://github.com/lightbend/mima/issues/423
      exclude[IncompatibleSignatureProblem]("cats.effect.Blocker.apply"),
      exclude[IncompatibleSignatureProblem]("cats.effect.Blocker.fromExecutorService")
    )
  }
)

val lawsMimaSettings = mimaSettings ++ Seq(
  // We broke binary compatibility for laws in 2.0
  mimaPreviousArtifacts := Set(organization.value %% name.value % "2.0.0")
)

lazy val cmdlineProfile = sys.env.getOrElse("SBT_PROFILE", "")

lazy val scalaJSSettings = Seq(
  // Use globally accessible (rather than local) source paths in JS source maps
  scalacOptions += {
    val hasVersion = git.gitCurrentTags.value.map(git.gitTagToVersionNumber.value).flatten.nonEmpty
    val versionOrHash =
      if (hasVersion)
        s"v${version.value}"
      else
        git.gitHeadCommit.value.get

    val l = (baseDirectory in LocalRootProject).value.toURI.toString
    val g = s"https://raw.githubusercontent.com/typelevel/cats-effect/$versionOrHash/"
    s"-P:scalajs:mapSourceURI:$l->$g"
  },
  // Work around "dropping dependency on node with no phase object: mixin"
  scalacOptions in (Compile, doc) -= "-Xfatal-warnings"
)

lazy val skipOnPublishSettings =
  Seq(skip in publish := true, publish := (()), publishLocal := (()), publishArtifact := false, publishTo := None)

lazy val sharedSourcesSettings = Seq(
  unmanagedSourceDirectories in Compile += {
    baseDirectory.value.getParentFile / "shared" / "src" / "main" / "scala"
  },
  unmanagedSourceDirectories in Test += {
    baseDirectory.value.getParentFile / "shared" / "src" / "test" / "scala"
  }
)

lazy val root = project
  .in(file("."))
  .disablePlugins(MimaPlugin)
  .aggregate(coreJVM, coreJS, lawsJVM, lawsJS)
  .settings(skipOnPublishSettings)

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .in(file("core"))
  .settings(commonSettings: _*)
  .settings(
    name := "cats-effect",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % CatsVersion,
      "org.typelevel" %%% "simulacrum" % SimulacrumVersion % CompileTime,
      "org.typelevel" %%% "cats-laws" % CatsVersion % Test,
      "org.typelevel" %%% "discipline-scalatest" % DisciplineScalatestVersion % Test
    ),
    libraryDependencies ++= Seq(
      compilerPlugin(("com.github.ghik" % "silencer-plugin" % SilencerVersion).cross(CrossVersion.full)),
      ("com.github.ghik" % "silencer-lib" % SilencerVersion % CompileTime).cross(CrossVersion.full),
      ("com.github.ghik" % "silencer-lib" % SilencerVersion % Test).cross(CrossVersion.full)
    ),
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, v)) if v <= 12 =>
          Seq(
            compilerPlugin(("org.scalamacros" % "paradise" % "2.1.1").cross(CrossVersion.full))
          )
        case _ =>
          // if scala 2.13.0-M4 or later, macro annotations merged into scala-reflect
          // https://github.com/scala/scala/pull/6606
          Nil
      }
    }
  )
  .jvmConfigure(_.enablePlugins(AutomateHeaderPlugin))
  .jvmConfigure(_.settings(mimaSettings))
  .jsConfigure(_.enablePlugins(AutomateHeaderPlugin))
  .jsConfigure(_.settings(scalaJSSettings))
  .jvmSettings(
    skip.in(publish) := customScalaJSVersion.forall(_.startsWith("1.0"))
  )

lazy val coreJVM = core.jvm
lazy val coreJS = core.js

lazy val laws = crossProject(JSPlatform, JVMPlatform)
  .in(file("laws"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings: _*)
  .settings(
    name := "cats-effect-laws",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-laws" % CatsVersion,
      "org.typelevel" %%% "discipline-scalatest" % DisciplineScalatestVersion % Test
    )
  )
  .jvmConfigure(_.enablePlugins(AutomateHeaderPlugin))
  .jvmConfigure(_.settings(lawsMimaSettings))
  .jsConfigure(_.enablePlugins(AutomateHeaderPlugin))
  .jsConfigure(_.settings(scalaJSSettings))
  .jvmSettings(
    skip.in(publish) := customScalaJSVersion.forall(_.startsWith("1.0"))
  )

lazy val lawsJVM = laws.jvm
lazy val lawsJS = laws.js

lazy val benchmarksPrev = project
  .in(file("benchmarks/vPrev"))
  .settings(commonSettings ++ skipOnPublishSettings ++ sharedSourcesSettings)
  .settings(libraryDependencies += "org.typelevel" %% "cats-effect" % "2.0.0")
  .settings(scalacOptions ~= (_.filterNot(Set("-Xfatal-warnings", "-Ywarn-unused-import").contains)))
  .enablePlugins(JmhPlugin)

lazy val benchmarksNext = project
  .in(file("benchmarks/vNext"))
  .dependsOn(coreJVM)
  .settings(commonSettings ++ skipOnPublishSettings ++ sharedSourcesSettings)
  .settings(scalacOptions ~= (_.filterNot(Set("-Xfatal-warnings", "-Ywarn-unused-import").contains)))
  .enablePlugins(JmhPlugin)

lazy val docsMappingsAPIDir =
  settingKey[String]("Name of subdirectory in site target directory for api docs")

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
  micrositeCompilingDocsTool := WithMdoc,
  mdocIn := (sourceDirectory in Compile).value / "mdoc",
  fork in mdoc := true,
  scalacOptions in mdoc ~= (_.filterNot(
    Set(
      "-Xfatal-warnings",
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

lazy val microsite = project
  .in(file("site"))
  .enablePlugins(MicrositesPlugin, SiteScaladocPlugin, MdocPlugin)
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
