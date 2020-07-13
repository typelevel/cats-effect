/*
 * Copyright 2020 Typelevel
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

import java.io.File

ThisBuild / baseVersion := "3.0"

ThisBuild / organization := "org.typelevel"
ThisBuild / organizationName := "Typelevel"

ThisBuild / publishGithubUser := "djspiewak"
ThisBuild / publishFullName := "Daniel Spiewak"

val PrimaryOS = "ubuntu-latest"

val ScalaJSJava = "adopt@1.8"

ThisBuild / crossScalaVersions := Seq("0.25.0-RC2", "2.12.11", "2.13.2")

ThisBuild / githubWorkflowTargetBranches := Seq("ce3")      // for now

val LTSJava = "adopt@11"
val LatestJava = "adopt@14"
val GraalVM8 = "graalvm8@20.1.0"

ThisBuild / githubWorkflowJavaVersions := Seq(ScalaJSJava, LTSJava, LatestJava, GraalVM8)
ThisBuild / githubWorkflowOSes := Seq(PrimaryOS)

ThisBuild / githubWorkflowBuildPreamble +=
  WorkflowStep.Use(
    "actions", "setup-node", "v2.1.0",
    name = Some("Setup NodeJS v14 LTS"),
    params = Map("node-version" -> "14"),
    cond = Some("matrix.ci == 'ciJS'"))

ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("${{ matrix.ci }}")),

  WorkflowStep.Run(
    List("example/test-jvm.sh ${{ matrix.scala }}"),
    name = Some("Test Example JVM App Within Sbt"),
    cond = Some("matrix.ci == 'ciJVM'")),

  WorkflowStep.Run(
    List("example/test-js.sh ${{ matrix.scala }}"),
    name = Some("Test Example JavaScript App Using Node"),
    cond = Some("matrix.ci == 'ciJS'")))

ThisBuild / githubWorkflowBuildMatrixAdditions += "ci" -> List("ciJVM")

ThisBuild / githubWorkflowBuildMatrixInclusions ++=
  (ThisBuild / crossScalaVersions).value.filter(_.startsWith("2.")) map { scala =>
    MatrixInclude(
      Map("os" -> PrimaryOS, "java" -> ScalaJSJava, "scala" -> scala),
      Map("ci" -> "ciJS"))
  }

Global / homepage := Some(url("https://github.com/typelevel/cats-effect"))

Global / scmInfo := Some(
  ScmInfo(
    url("https://github.com/typelevel/cats-effect"),
    "git@github.com:typelevel/cats-effect.git"))

val CatsVersion = "2.2.0-RC1"
val Specs2Version = "4.9.4"
val DisciplineVersion = "1.1.0"

addCommandAlias("ciJVM", "; project rootJVM; headerCheck; clean; testIfRelevant; mimaReportBinaryIssuesIfRelevant")
addCommandAlias("ciJS", "; project rootJS; headerCheck; clean; testIfRelevant")

lazy val root = project.in(file("."))
  .aggregate(rootJVM, rootJS)
  .settings(noPublishSettings)

lazy val rootJVM = project
  .aggregate(kernel.jvm, testkit.jvm, laws.jvm, core.jvm, concurrent.jvm, example.jvm, benchmarks)
  .settings(noPublishSettings)

lazy val rootJS = project
  .aggregate(kernel.js, testkit.js, laws.js, core.js, concurrent.js, example.js)
  .settings(noPublishSettings)

/**
 * The core abstractions and syntax. This is the most general definition of Cats Effect,
 * without any concrete implementations. This is the "batteries not included" dependency.
 */
lazy val kernel = crossProject(JSPlatform, JVMPlatform).in(file("kernel"))
  .settings(
    name := "cats-effect-kernel",
    libraryDependencies += "org.typelevel" %%% "cats-core" % CatsVersion)
  .settings(dottyLibrarySettings)
  .settings(dottyJsSettings(ThisBuild / crossScalaVersions))


/**
 * Reference implementations (including a pure ConcurrentBracket), generic ScalaCheck
 * generators, and useful tools for testing code written against Cats Effect.
 */
lazy val testkit = crossProject(JSPlatform, JVMPlatform).in(file("testkit"))
  .dependsOn(kernel)
  .settings(
    name := "cats-effect-testkit",

    libraryDependencies ++= Seq(
      "org.typelevel"  %%% "cats-free"  % CatsVersion,
      "org.scalacheck" %%% "scalacheck" % "1.14.3"))
  .settings(dottyLibrarySettings)
  .settings(dottyJsSettings(ThisBuild / crossScalaVersions))
  .settings(libraryDependencies += "com.codecommit" %%% "coop" % "0.6.1")

/**
 * The laws which constrain the abstractions. This is split from kernel to avoid
 * jar file and dependency issues. As a consequence of this split, some things
 * which are defined in testkit are *tested* in the Test scope of this project.
 */
lazy val laws = crossProject(JSPlatform, JVMPlatform).in(file("laws"))
  .dependsOn(kernel, testkit % Test)
  .settings(
    name := "cats-effect-laws",

    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-laws" % CatsVersion,

      "org.typelevel" %%% "discipline-specs2" % DisciplineVersion % Test,
      "org.specs2"    %%% "specs2-scalacheck" % Specs2Version % Test))
  .settings(dottyLibrarySettings)
  .settings(dottyJsSettings(ThisBuild / crossScalaVersions))

/**
 * Concrete, production-grade implementations of the abstractions. Or, more
 * simply-put: IO and Resource. Also contains some general datatypes built
 * on top of IO which are useful in their own right, as well as some utilities
 * (such as IOApp). This is the "batteries included" dependency.
 */
lazy val core = crossProject(JSPlatform, JVMPlatform).in(file("core"))
  .dependsOn(kernel, concurrent, laws % Test, testkit % Test)
  .settings(
    name := "cats-effect",

    libraryDependencies ++= Seq(
      "org.typelevel" %%% "discipline-specs2" % DisciplineVersion % Test,
      "org.specs2"    %%% "specs2-scalacheck" % Specs2Version     % Test,
      "org.typelevel" %%% "cats-kernel-laws"  % CatsVersion       % Test))
  .jvmSettings(
    Test / fork := true,
    Test / javaOptions += s"-Dsbt.classpath=${(Test / fullClasspath).value.map(_.data.getAbsolutePath).mkString(File.pathSeparator)}")
  .settings(dottyLibrarySettings)
  .settings(dottyJsSettings(ThisBuild / crossScalaVersions))

/**
 * Implementations of concurrent data structures (Ref, MVar, etc) purely in
 * terms of cats effect typeclasses (no dependency on IO)
 */
lazy val concurrent = crossProject(JSPlatform, JVMPlatform).in(file("concurrent"))
  .dependsOn(kernel)
  .settings(
    name := "cats-effect-concurrent",
    libraryDependencies ++= Seq(
      "org.specs2"    %%% "specs2-scalacheck" % Specs2Version % Test
    )
  )
  .settings(dottyLibrarySettings)
  .settings(dottyJsSettings(ThisBuild / crossScalaVersions))

/**
 * A trivial pair of trivial example apps primarily used to show that IOApp
 * works as a practical runtime on both target platforms.
 */
lazy val example = crossProject(JSPlatform, JVMPlatform).in(file("example"))
  .dependsOn(core)
  .settings(name := "cats-effect-example")
  .jsSettings(scalaJSUseMainModuleInitializer := true)
  .settings(noPublishSettings)
  .settings(dottyJsSettings(ThisBuild / crossScalaVersions))

/**
 * JMH benchmarks for IO and other things.
 */
lazy val benchmarks = project.in(file("benchmarks"))
  .dependsOn(core.jvm)
  .settings(name := "cats-effect-benchmarks")
  .settings(noPublishSettings)
  .enablePlugins(JmhPlugin)
