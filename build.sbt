/*
 * Copyright (c) 2017-2022 The Typelevel Cats-effect Project Developers
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

import sbtghactions.UseRef

import scala.util.Try
import scala.sys.process._

ThisBuild / baseVersion := "2.6"

val Scala212 = "2.12.15"
val Scala213 = "2.13.7"
val Scala3 = "3.0.2"

ThisBuild / crossScalaVersions := Seq(Scala3, Scala212, Scala213)
ThisBuild / scalaVersion := (ThisBuild / crossScalaVersions).value.last

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("8"), JavaSpec.temurin("11"), JavaSpec.temurin("17"))

ThisBuild / githubWorkflowTargetBranches := Seq("series/2.x")

ThisBuild / githubWorkflowBuild +=
  WorkflowStep.Sbt(List("docs/mdoc"), cond = Some(s"matrix.scala == '$Scala213'"))

ThisBuild / githubWorkflowBuild +=
  WorkflowStep.Run(
    List("cd scalafix", "sbt test"),
    name = Some("Scalafix tests"),
    cond = Some(s"matrix.scala == '$Scala213'")
  )

ThisBuild / organization := "org.typelevel"
ThisBuild / organizationName := "Typelevel"
ThisBuild / startYear := Some(2017)
ThisBuild / endYear := Some(2022)

ThisBuild / developers := List(
  Developer("djspiewak", "Daniel Spiewak", "", url("https://github.com/djspiewak")),
  Developer("mpilquist", "Michael Pilquist", "", url("https://github.com/mpilquist")),
  Developer("alexelcu", "Alexandru Nedelcu", "", url("https://alexn.org")),
  Developer("SystemFw", "Fabio Labella", "", url("https://github.com/systemfw"))
)

ThisBuild / homepage := Some(url("https://typelevel.org/cats-effect/"))
ThisBuild / scmInfo := Some(
  ScmInfo(url("https://github.com/typelevel/cats-effect"), "git@github.com:typelevel/cats-effect.git")
)

val CatsVersion = "2.7.0"
val DisciplineMunitVersion = "1.0.9"

replaceCommandAlias(
  "ci",
  "; project /; headerCheck ;scalafmtSbtCheck ;scalafmtCheckAll; clean; testIfRelevant; mimaReportBinaryIssuesIfRelevant; doc"
)

replaceCommandAlias(
  "release",
  "; reload; project /; +mimaReportBinaryIssuesIfRelevant; +publishIfRelevant; sonatypeBundleRelease"
)

val commonSettings = Seq(
  Compile / doc / scalacOptions ++= {
    val isSnapshot = git.gitCurrentTags.value.map(git.gitTagToVersionNumber.value).flatten.isEmpty

    val path =
      if (isSnapshot)
        scmInfo.value.get.browseUrl + "/blob/" + git.gitHeadCommit.value.get + "€{FILE_PATH}.scala"
      else
        scmInfo.value.get.browseUrl + "/blob/v" + version.value + "€{FILE_PATH}.scala"

    Seq("-doc-source-url", path, "-sourcepath", (LocalRootProject / baseDirectory).value.getAbsolutePath)
  },
  Compile / doc / sources := (Compile / doc / sources).value,
  Compile / doc / scalacOptions ++=
    Seq("-doc-root-content", (baseDirectory.value.getParentFile / "shared" / "rootdoc.txt").getAbsolutePath),
  Compile / doc / scalacOptions ++=
    Opts.doc.title("cats-effect"),
  Test / scalacOptions ++= { if (isDotty.value) Seq() else Seq("-Yrangepos") },
  Test / scalacOptions ~= (_.filterNot(Set("-Wvalue-discard", "-Ywarn-value-discard"))),
  // Disable parallel execution in tests; otherwise we cannot test System.err
  Test / parallelExecution := false,
  Test / testForkedParallel := false,
  testFrameworks += new TestFramework("munit.Framework"),
  Global / concurrentRestrictions += Tags.limit(Tags.Test, 1),
  headerLicense := Some(HeaderLicense.Custom("""|Copyright (c) 2017-2022 The Typelevel Cats-effect Project Developers
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
                                                |limitations under the License.""".stripMargin))
)

val mimaSettings = Seq(
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
      exclude[IncompatibleSignatureProblem]("cats.effect.Blocker.fromExecutorService"),
      // Tracing - https://github.com/typelevel/cats-effect/pull/854
      exclude[DirectMissingMethodProblem]("cats.effect.IO#Async.apply"),
      exclude[DirectMissingMethodProblem]("cats.effect.IO#Bind.apply"),
      exclude[IncompatibleResultTypeProblem]("cats.effect.IO#Async.k"),
      exclude[DirectMissingMethodProblem]("cats.effect.IO#Async.copy"),
      exclude[IncompatibleResultTypeProblem]("cats.effect.IO#Async.copy$default$1"),
      exclude[DirectMissingMethodProblem]("cats.effect.IO#Async.this"),
      exclude[DirectMissingMethodProblem]("cats.effect.IO#Bind.copy"),
      exclude[DirectMissingMethodProblem]("cats.effect.IO#Bind.this"),
      exclude[DirectMissingMethodProblem]("cats.effect.IO#Map.index"),
      exclude[IncompatibleMethTypeProblem]("cats.effect.IO#Map.copy"),
      exclude[IncompatibleResultTypeProblem]("cats.effect.IO#Map.copy$default$3"),
      exclude[IncompatibleMethTypeProblem]("cats.effect.IO#Map.this"),
      exclude[IncompatibleMethTypeProblem]("cats.effect.IO#Map.apply"),
      // revise Deferred, MVarConcurrent, LinkedLongMap - https://github.com/typelevel/cats-effect/pull/918
      exclude[IncompatibleResultTypeProblem]("cats.effect.concurrent.Deferred#State#Unset.waiting"),
      exclude[DirectMissingMethodProblem]("cats.effect.concurrent.Deferred#State#Unset.copy"),
      exclude[IncompatibleResultTypeProblem]("cats.effect.concurrent.Deferred#State#Unset.copy$default$1"),
      exclude[DirectMissingMethodProblem]("cats.effect.concurrent.Deferred#State#Unset.this"),
      exclude[MissingClassProblem]("cats.effect.concurrent.Deferred$Id"),
      exclude[DirectMissingMethodProblem]("cats.effect.concurrent.Deferred#State#Unset.apply"),
      // simulacrum shims
      exclude[DirectMissingMethodProblem]("cats.effect.Concurrent#ops.<clinit>"),
      exclude[DirectMissingMethodProblem]("cats.effect.Sync#ops.<clinit>"),
      exclude[DirectMissingMethodProblem]("cats.effect.Async#ops.<clinit>"),
      exclude[DirectMissingMethodProblem]("cats.effect.Effect#ops.<clinit>"),
      exclude[DirectMissingMethodProblem]("cats.effect.ConcurrentEffect#ops.<clinit>"),
      // abstract method defined in all subtypes of sealed abstract class
      exclude[ReversedMissingMethodProblem]("cats.effect.Resource.invariant"),
      // add cached tracing support for `IO.delay` and `IO.defer`
      exclude[DirectMissingMethodProblem]("cats.effect.IO#Delay.apply"),
      exclude[DirectMissingMethodProblem]("cats.effect.IO#Delay.copy"),
      exclude[DirectMissingMethodProblem]("cats.effect.IO#Delay.this"),
      exclude[DirectMissingMethodProblem]("cats.effect.IO#Suspend.apply"),
      exclude[DirectMissingMethodProblem]("cats.effect.IO#Suspend.copy"),
      exclude[DirectMissingMethodProblem]("cats.effect.IO#Suspend.this")
    )
  }
)

lazy val scalaJSSettings = Seq(
  // Use globally accessible (rather than local) source paths in JS source maps
  scalacOptions ++= {
    val hasVersion = git.gitCurrentTags.value.map(git.gitTagToVersionNumber.value).flatten.nonEmpty

    val maybeVersionOrHash =
      if (hasVersion)
        Some(s"v${version.value}")
      else
        git.gitHeadCommit.value

    maybeVersionOrHash match {
      case Some(versionOrHash) =>
        val l = (LocalRootProject / baseDirectory).value.toURI.toString
        val g = s"https://raw.githubusercontent.com/typelevel/cats-effect/$versionOrHash/"
        Seq(s"-P:scalajs:mapSourceURI:$l->$g")

      case None =>
        Seq()
    }
  },
  // Work around "dropping dependency on node with no phase object: mixin"
  Compile / doc / scalacOptions -= "-Xfatal-warnings",
  scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
  // Dotty dislikes these -P flags, warns against them
  scalacOptions := {
    scalacOptions.value.filterNot { s =>
      if (isDotty.value) s.startsWith("-P:scalajs:mapSourceURI")
      else false
    }
  }
)

lazy val root = project
  .in(file("."))
  .enablePlugins(NoPublishPlugin)
  .aggregate(coreJVM, coreJS, lawsJVM, lawsJS, runtimeTests)
  .settings(crossScalaVersions := Seq(), scalaVersion := Scala212)

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .in(file("core"))
  .settings(commonSettings: _*)
  .settings(
    name := "cats-effect",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % CatsVersion,
      "org.typelevel" %%% "cats-laws" % CatsVersion % Test,
      "org.typelevel" %%% "discipline-munit" % DisciplineMunitVersion % Test
    )
  )
  .jvmSettings(mimaSettings)
  .jvmSettings(
    mimaPreviousArtifacts := {
      // disable mima check on dotty for now
      if (isDotty.value) Set.empty else mimaPreviousArtifacts.value
    },
    mimaFailOnNoPrevious := !isDotty.value,
    javacOptions ++= Seq("-source", "1.8", "-target", "1.8")
  )
  .jsSettings(scalaJSSettings)

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
      "org.typelevel" %%% "discipline-munit" % DisciplineMunitVersion % Test
    )
  )
  .jvmSettings(
    mimaPreviousArtifacts := {
      // disable mima check on dotty for now
      if (isDotty.value) Set.empty else mimaPreviousArtifacts.value
    },
    mimaFailOnNoPrevious := !isDotty.value
  )
  .jsSettings(scalaJSSettings)

lazy val lawsJVM = laws.jvm
lazy val lawsJS = laws.js

lazy val FullTracingTest = config("fulltracing").extend(Test)

lazy val runtimeTests = project
  .in(file("runtime-tests"))
  .enablePlugins(NoPublishPlugin)
  .dependsOn(coreJVM % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-laws" % CatsVersion,
      "org.typelevel" %%% "discipline-munit" % DisciplineMunitVersion % Test
    )
  )
  .configs(FullTracingTest)
  .settings(inConfig(FullTracingTest)(Defaults.testSettings): _*)
  .settings(
    FullTracingTest / unmanagedSourceDirectories += {
      baseDirectory.value.getParentFile / "src" / "fulltracing" / "scala"
    },
    Test / test := (Test / test).dependsOn(FullTracingTest / test).value,
    Test / fork := true,
    FullTracingTest / fork := true,
    Test / javaOptions ++= Seq(
      "-Dcats.effect.tracing=true",
      "-Dcats.effect.stackTracingMode=cached"
    ),
    FullTracingTest / javaOptions ++= Seq(
      "-Dcats.effect.tracing=true",
      "-Dcats.effect.stackTracingMode=full"
    )
  )

// Custom shared sources settings for benchmark projects
lazy val sharedSourcesSettings = Seq(
  Compile / unmanagedSourceDirectories += {
    baseDirectory.value.getParentFile / "shared" / "src" / "main" / "scala"
  },
  Test / unmanagedSourceDirectories += {
    baseDirectory.value.getParentFile / "shared" / "src" / "test" / "scala"
  }
)

lazy val benchmarksPrev = project
  .in(file("benchmarks/vPrev"))
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings ++ sharedSourcesSettings)
  .settings(libraryDependencies += "org.typelevel" %% "cats-effect" % "2.4.0")
  .settings(scalacOptions ~= (_.filterNot(Set("-Xfatal-warnings", "-Ywarn-unused-import").contains)))
  .enablePlugins(JmhPlugin)

lazy val benchmarksNext = project
  .in(file("benchmarks/vNext"))
  .enablePlugins(NoPublishPlugin)
  .dependsOn(coreJVM)
  .settings(commonSettings ++ sharedSourcesSettings)
  .settings(scalacOptions ~= (_.filterNot(Set("-Xfatal-warnings", "-Ywarn-unused-import").contains)))
  .enablePlugins(JmhPlugin)

lazy val docs = project
  .in(file("site-docs"))
  .enablePlugins(MdocPlugin)
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)
  .settings(
    mdoc / fork := true,
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
    ))
  )
  .dependsOn(coreJVM, lawsJVM)

git.gitHeadCommit := Try("git rev-parse HEAD".!!.trim).toOption
git.gitCurrentTags := Try("git tag --contains HEAD".!!.trim.split("\\s+").toList).toOption.toList.flatten

Global / excludeLintKeys += (docs / mdoc / fork)
