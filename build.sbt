/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

import microsites.ExtraMdFileConfig
import scala.sys.process._
import scala.xml.Elem
import scala.xml.transform.{RewriteRule, RuleTransformer}

organization in ThisBuild := "org.typelevel"
organizationName in ThisBuild := "Typelevel"
startYear in ThisBuild := Some(2017)

val CompileTime = config("CompileTime").hide

val CatsVersion = "1.1.0"
val SimulacrumVersion = "0.11.0"

val ScalaTestVersion = "3.0.4"
val ScalaCheckVersion = "1.13.5"
val DisciplineVersion = "0.8"

addCommandAlias("ci", ";test ;mimaReportBinaryIssues; doc")
addCommandAlias("release", ";project root ;reload ;+publishSigned ;sonatypeReleaseAll ;microsite/publishMicrosite")

val commonSettings = Seq(
  crossScalaVersions := Seq("2.11.12", "2.12.4"),

  scalacOptions in (Compile, console) ~= (_ filterNot Set("-Xfatal-warnings", "-Ywarn-unused-import").contains),

  scalacOptions in (Compile, doc) ++= {
    val isSnapshot = git.gitCurrentTags.value.map(git.gitTagToVersionNumber.value).flatten.isEmpty

    val path = if (isSnapshot)
      scmInfo.value.get.browseUrl + "/blob/" + git.gitHeadCommit.value.get + "€{FILE_PATH}.scala"
    else
      scmInfo.value.get.browseUrl + "/blob/v" + version.value + "€{FILE_PATH}.scala"

    Seq("-doc-source-url", path, "-sourcepath", baseDirectory.in(LocalRootProject).value.getAbsolutePath)
  },

  sources in (Compile, doc) :=
    (sources in (Compile, doc)).value,

  scalacOptions in (Compile, doc) ++=
    Seq("-doc-root-content", (baseDirectory.value.getParentFile / "shared" / "rootdoc.txt").getAbsolutePath),
  scalacOptions in (Compile, doc) ++=
    Opts.doc.title("cats-effect"),

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

  isSnapshot := version.value endsWith "SNAPSHOT",      // so… sonatype doesn't like git hash snapshots

  publishTo := Some(
    if (isSnapshot.value)
      Opts.resolver.sonatypeSnapshots
    else
      Opts.resolver.sonatypeStaging),

  publishMavenStyle := true,
  pomIncludeRepository := { _ => false },

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
    </developers>,

  homepage := Some(url("https://typelevel.org/cats-effect/")),
  scmInfo := Some(ScmInfo(url("https://github.com/typelevel/cats-effect"), "git@github.com:typelevel/cats-effect.git")),
  headerLicense := Some(HeaderLicense.Custom(
    """|Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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
       |limitations under the License."""
      .stripMargin)),

  // For evicting Scoverage out of the generated POM
  // See: https://github.com/scoverage/sbt-scoverage/issues/153
  pomPostProcess := { (node: xml.Node) =>
    new RuleTransformer(new RewriteRule {
      override def transform(node: xml.Node): Seq[xml.Node] = node match {
        case e: Elem
          if e.label == "dependency" && e.child.exists(child => child.label == "groupId" && child.text == "org.scoverage") => Nil
        case _ => Seq(node)
      }
    }).transform(node).head
  },

  addCompilerPlugin("org.spire-math" % "kind-projector" % "0.9.5" cross CrossVersion.binary)
)

val mimaSettings = Seq(
  // Setting the previous artifact manually at 0.9
  // as a temporary measure until we release 0.10
  mimaPreviousArtifacts := Set(organization.value %% name.value % "0.10"),
  /*
  mimaPreviousArtifacts := {
    val TagBase = """^(\d+)\.(\d+).*"""r
    val TagBase(major, minor) = BaseVersion

    val tags = "git tag --list".!! split "\n" map { _.trim }
    val versions =
      tags filter { _ startsWith s"v$major.$minor" } map { _ substring 1 }

    versions.map { v => organization.value %% name.value % v }.toSet
  },*/
  mimaBinaryIssueFilters ++= {
    import com.typesafe.tools.mima.core._
    import com.typesafe.tools.mima.core.ProblemFilters._
    Seq(
      exclude[DirectMissingMethodProblem]("cats.effect.Sync#StateTSync.map"),
      exclude[IncompatibleMethTypeProblem]("cats.effect.Sync#StateTSync.map"),
      exclude[InheritedNewAbstractMethodProblem]("cats.effect.Bracket.bracket"),
      exclude[InheritedNewAbstractMethodProblem]("cats.effect.Bracket.bracketCase"),
      exclude[ReversedMissingMethodProblem]("cats.effect.Sync#OptionTSync.bracketCase"),
      exclude[ReversedMissingMethodProblem]("cats.effect.Sync#WriterTSync.bracketCase"),
      exclude[ReversedMissingMethodProblem]("cats.effect.Sync#StateTSync.bracketCase"),
      exclude[ReversedMissingMethodProblem]("cats.effect.Sync#EitherTSync.bracketCase"),
      exclude[ReversedMissingMethodProblem]("cats.effect.Sync#KleisliSync.bracketCase"),
      exclude[MissingClassProblem]("cats.effect.internals.AndThen"),
      exclude[MissingClassProblem]("cats.effect.internals.AndThen$"),
      exclude[MissingClassProblem]("cats.effect.internals.AndThen$Concat"),
      exclude[MissingClassProblem]("cats.effect.internals.AndThen$Concat$"),
      exclude[MissingClassProblem]("cats.effect.internals.AndThen$Single"),
      exclude[MissingClassProblem]("cats.effect.internals.AndThen$Single$"),
      exclude[ReversedMissingMethodProblem]("cats.effect.Async.never"),
      exclude[DirectMissingMethodProblem]("cats.effect.Sync.catsEitherTEvalSync"),
      exclude[ReversedMissingMethodProblem]("cats.effect.Effect.runSyncStep"),
      exclude[ReversedMissingMethodProblem]("cats.effect.Effect#StateTEffect.runSyncStep"),
      exclude[ReversedMissingMethodProblem]("cats.effect.Effect#WriterTEffect.runSyncStep"),
      exclude[ReversedMissingMethodProblem]("cats.effect.Effect#EitherTEffect.runSyncStep"),
      exclude[ReversedMissingMethodProblem]("cats.effect.Effect#Ops.runSyncStep"),
      //
      // Following are all internal implementation details:
      //
      // Not a problem: IO.Async is a private class
      exclude[IncompatibleMethTypeProblem]("cats.effect.IO#Async.apply"),
      // Not a problem: IO.Async is a private class
      exclude[MissingTypesProblem]("cats.effect.Async$"),
      // Not a problem: IO.Async is a private class
      exclude[IncompatibleResultTypeProblem]("cats.effect.IO#Async.k"),
      // Not a problem: IO.Async is a private class
      exclude[IncompatibleMethTypeProblem]("cats.effect.IO#Async.copy"),
      // Not a problem: IO.Async is a private class
      exclude[IncompatibleResultTypeProblem]("cats.effect.IO#Async.copy$default$1"),
      // Not a problem: IO.Async is a private class
      exclude[IncompatibleMethTypeProblem]("cats.effect.IO#Async.this"),
      // Not a problem: RestartCallback is a private class
      exclude[DirectMissingMethodProblem]("cats.effect.internals.IORunLoop#RestartCallback.this"),
      // Not a problem: IOPlatform is private
      exclude[DirectMissingMethodProblem]("cats.effect.internals.IOPlatform.onceOnly"),
      // Not a problem: IORunLoop is private
      exclude[MissingClassProblem]("cats.effect.internals.IORunLoop$RestartCallback$"),
      // Not a problem: Async.never implementation is just moved
      exclude[ReversedMissingMethodProblem]("cats.effect.Async.never"),
      // Deleted
      exclude[DirectMissingMethodProblem]("cats.effect.internals.IOFrame.errorHandler")
    )
  })

lazy val cmdlineProfile = sys.env.getOrElse("SBT_PROFILE", "")

def profile: Project => Project = pr => cmdlineProfile match {
  case "coverage" => pr
  case _ => pr.disablePlugins(scoverage.ScoverageSbtPlugin)
}

lazy val scalaJSSettings = Seq(
  coverageExcludedFiles := ".*")

lazy val skipOnPublishSettings = Seq(
  skip in publish := true,
  publish := (()),
  publishLocal := (()),
  publishArtifact := false,
  publishTo := None)

lazy val sharedSourcesSettings = Seq(
  unmanagedSourceDirectories in Compile += {
    baseDirectory.value.getParentFile / "shared" / "src" / "main" / "scala"
  },
  unmanagedSourceDirectories in Test += {
    baseDirectory.value.getParentFile / "shared" / "src" / "test" / "scala"
  })

lazy val root = project.in(file("."))
  .aggregate(coreJVM, coreJS, lawsJVM, lawsJS)
  .configure(profile)
  .settings(skipOnPublishSettings)

lazy val core = crossProject.in(file("core"))
  .settings(commonSettings: _*)
  .settings(
    name := "cats-effect",

    libraryDependencies ++= Seq(
      "org.typelevel"        %%% "cats-core"  % CatsVersion,
      "com.github.mpilquist" %%% "simulacrum" % SimulacrumVersion % CompileTime,

      "org.typelevel"  %%% "cats-laws"  % CatsVersion       % "test",
      "org.scalatest"  %%% "scalatest"  % ScalaTestVersion  % "test",
      "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion % "test",
      "org.typelevel"  %%% "discipline" % DisciplineVersion % "test"),

    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full))
  .jvmConfigure(_.enablePlugins(AutomateHeaderPlugin))
  .jvmConfigure(_.settings(mimaSettings))
  .jsConfigure(_.enablePlugins(AutomateHeaderPlugin))
  .jvmConfigure(profile)
  .jsConfigure(_.settings(scalaJSSettings))

lazy val coreJVM = core.jvm
lazy val coreJS = core.js

lazy val laws = crossProject
  .in(file("laws"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings: _*)
  .settings(
    name := "cats-effect-laws",

    libraryDependencies ++= Seq(
      "org.typelevel"  %%% "cats-laws"  % CatsVersion,
      "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion,
      "org.typelevel"  %%% "discipline" % DisciplineVersion,

      "org.scalatest"  %%% "scalatest"  % ScalaTestVersion % "test"))
  .jvmConfigure(_.enablePlugins(AutomateHeaderPlugin))
  .jsConfigure(_.enablePlugins(AutomateHeaderPlugin))
  .jvmConfigure(profile)
  .jsConfigure(_.settings(scalaJSSettings))

lazy val lawsJVM = laws.jvm
lazy val lawsJS = laws.js

lazy val benchmarksPrev = project.in(file("benchmarks/vPrev"))
  .configure(profile)
  .settings(commonSettings ++ skipOnPublishSettings ++ sharedSourcesSettings)
  .settings(libraryDependencies += "org.typelevel" %% "cats-effect" % "0.5")
  .enablePlugins(JmhPlugin)

lazy val benchmarksNext = project.in(file("benchmarks/vNext"))
  .configure(profile)
  .dependsOn(coreJVM)
  .settings(commonSettings ++ skipOnPublishSettings ++ sharedSourcesSettings)
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
    "white-color" -> "#FFFFFF"),
  micrositeExtraMdFiles := Map(
    file("README.md") -> ExtraMdFileConfig(
      "index.md",
      "home",
      Map("section" -> "home", "position" -> "0")
    )
  ),
  fork in tut := true,
  scalacOptions in Tut --= Seq(
    "-Xfatal-warnings",
    "-Ywarn-unused-import",
    "-Ywarn-numeric-widen",
    "-Ywarn-dead-code",
    "-Xlint:-missing-interpolator,_",
  ),
  docsMappingsAPIDir := "api",
  addMappingsToSiteDir(mappings in packageDoc in Compile in coreJVM, docsMappingsAPIDir)
)

lazy val microsite = project.in(file("site"))
  .enablePlugins(MicrositesPlugin)
  .enablePlugins(SiteScaladocPlugin)
  .settings(commonSettings ++ skipOnPublishSettings ++ sharedSourcesSettings)
  .settings(siteSettings)
  .dependsOn(coreJVM, lawsJVM)

/*
 * Compatibility version.  Use this to declare what version with
 * which `master` remains in compatibility.  This is literally
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
 * prior to the release of 0.2.  Then the base version again remains
 * 0.2-compatible until that compatibility is broken, with the major
 * version bump of 1.0.  Again, this is all to avoid pre-committing
 * to a major/minor bump before the work is done (see: Scala 2.8).
 */
val BaseVersion = "0.10"

licenses in ThisBuild += ("Apache-2.0", url("http://www.apache.org/licenses/"))

/***********************************************************************\
                      Boilerplate below these lines
\***********************************************************************/

coursierUseSbtCredentials in ThisBuild := true
coursierChecksums in ThisBuild := Nil      // workaround for nexus sync bugs

// Adapted from Rob Norris' post at https://tpolecat.github.io/2014/04/11/scalac-flags.html
scalacOptions in ThisBuild ++= Seq(
  "-language:_",
  "-deprecation",
  "-encoding", "UTF-8", // yes, this is 2 args
  "-feature",
  "-unchecked",
  "-Xfatal-warnings",
  "-Yno-adapted-args",
  "-Ywarn-dead-code"
)

scalacOptions in ThisBuild ++= Seq(
  "-Ywarn-unused-import",
  "-Ywarn-numeric-widen",
  "-Xlint:-missing-interpolator,_"
)

scalacOptions in Test += "-Yrangepos"

useGpg := true

enablePlugins(GitVersioning)

val ReleaseTag = """^v(\d+\.\d+(?:\.\d+(?:[-.]\w+)?)?)$""".r

git.baseVersion := BaseVersion

git.gitTagToVersionNumber := {
  case ReleaseTag(v) => Some(v)
  case _ => None
}

git.formattedShaVersion := {
  val suffix = git.makeUncommittedSignifierSuffix(git.gitUncommittedChanges.value, git.uncommittedSignifier.value)

  git.gitHeadCommit.value map { _.substring(0, 7) } map { sha =>
    git.baseVersion.value + "-" + sha + suffix
  }
}
