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

import org.openqa.selenium.firefox.FirefoxOptions
import org.scalajs.jsenv.selenium.SeleniumJSEnv

ThisBuild / baseVersion := "3.0"

ThisBuild / organization := "org.typelevel"
ThisBuild / organizationName := "Typelevel"

ThisBuild / developers := List(
  Developer("djspiewak", "Daniel Spiewak", "@djspiewak", url("https://github.com/djspiewak")),
  Developer("SystemFw", "Fabio Labella", "", url("https://github.com/SystemFw")),
  Developer("RaasAhsan", "Raas Ahsan", "", url("https://github.com/RaasAhsan")),
  Developer("TimWSpence", "Tim Spence", "@TimWSpence", url("https://github.com/TimWSpence")),
  Developer("kubukoz", "Jakub Kozłowski", "@kubukoz", url("https://github.com/kubukoz")),
  Developer("mpilquist", "Michael Pilquist", "@mpilquist", url("https://github.com/mpilquist")),
  Developer("vasilmkd", "Vasil Vasilev", "@vasilvasilev97", url("https://github.com/vasilmkd")),
  Developer("bplommer", "Ben Plommer", "@bplommer", url("https://github.com/bplommer")),
  Developer("gvolpe", "Gabriel Volpe", "@volpegabriel87", url("https://github.com/gvolpe"))
)

val PrimaryOS = "ubuntu-latest"
val Windows = "windows-latest"

val ScalaJSJava = "adopt@1.8"
val Scala213 = "2.13.3"

ThisBuild / crossScalaVersions := Seq("3.0.0-M1", "3.0.0-M2", "2.12.12", Scala213)

ThisBuild / githubWorkflowTargetBranches := Seq("series/3.x")

val LTSJava = "adopt@1.11"
val LatestJava = "adopt@1.15"
val GraalVM8 = "graalvm-ce-java8@20.2.0"

ThisBuild / githubWorkflowJavaVersions := Seq(ScalaJSJava, LTSJava, LatestJava, GraalVM8)
ThisBuild / githubWorkflowOSes := Seq(PrimaryOS, Windows)

ThisBuild / githubWorkflowBuildPreamble +=
  WorkflowStep.Use(
    "actions", "setup-node", "v2.1.2",
    name = Some("Setup NodeJS v14 LTS"),
    params = Map("node-version" -> "14"),
    cond = Some("matrix.ci == 'ciJS'"))

ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("${{ matrix.ci }}")),

  WorkflowStep.Sbt(
    List("docs/mdoc"),
    cond = Some(s"matrix.scala == '$Scala213' && matrix.ci == 'ciJVM'")),

  WorkflowStep.Run(
    List("example/test-jvm.sh ${{ matrix.scala }}"),
    name = Some("Test Example JVM App Within Sbt"),
    cond = Some(s"matrix.ci == 'ciJVM' && matrix.os == '$PrimaryOS'")),

  WorkflowStep.Run(
    List("example/test-js.sh ${{ matrix.scala }}"),
    name = Some("Test Example JavaScript App Using Node"),
    cond = Some(s"matrix.ci == 'ciJS' && matrix.os == '$PrimaryOS'")))

val ciVariants = List("ciJVM", "ciJS", "ciFirefox")
ThisBuild / githubWorkflowBuildMatrixAdditions += "ci" -> ciVariants

ThisBuild / githubWorkflowBuildMatrixExclusions ++= {
  val windowsScalaFilters = (ThisBuild / githubWorkflowScalaVersions).value.filterNot(Set(Scala213)).map { scala =>
    MatrixExclude(Map("os" -> Windows, "scala" -> scala))
  }

  Seq("ciJS", "ciFirefox").flatMap { ci =>
    val javaFilters = (ThisBuild / githubWorkflowJavaVersions).value.filterNot(Set(ScalaJSJava)).map { java =>
      MatrixExclude(Map("ci" -> ci, "java" -> java))
    }

    javaFilters ++ windowsScalaFilters :+ MatrixExclude(Map("os" -> Windows, "ci" -> ci))
  }
}

ThisBuild / githubWorkflowBuildMatrixExclusions ++= Seq(
  MatrixExclude(Map("java" -> LatestJava, "scala" -> "3.0.0-M1")),
  MatrixExclude(Map("java" -> LatestJava, "os" -> Windows))
)

lazy val useFirefoxEnv = settingKey[Boolean]("Use headless Firefox (via geckodriver) for running tests")
Global / useFirefoxEnv := false

ThisBuild / Test / jsEnv := {
  val old = (Test / jsEnv).value

  if (useFirefoxEnv.value) {
    val options = new FirefoxOptions()
    options.addArguments("-headless")
    new SeleniumJSEnv(options)
  } else {
    old
  }
}

ThisBuild / homepage := Some(url("https://github.com/typelevel/cats-effect"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/typelevel/cats-effect"),
    "git@github.com:typelevel/cats-effect.git"))

val CatsVersion = "2.3.0"
val Specs2Version = "4.10.5"
val ScalaCheckVersion = "1.15.1"
val DisciplineVersion = "1.1.2"

replaceCommandAlias("ci", "; project /; headerCheck; scalafmtCheck; clean; testIfRelevant; coreJVM/mimaReportBinaryIssues; set Global / useFirefoxEnv := true; coreJS/test; set Global / useFirefoxEnv := false")
addCommandAlias("ciAll", "; project /; +headerCheck; +scalafmtCheck; +clean; +testIfRelevant; +coreJVM/mimaReportBinaryIssues; set Global / useFirefoxEnv := true; +coreJS/test; set Global / useFirefoxEnv := false")

addCommandAlias("ciJVM", "; project rootJVM; headerCheck; scalafmtCheck; clean; test; mimaReportBinaryIssues")
addCommandAlias("ciJS", "; project rootJS; headerCheck; scalafmtCheck; clean; test")

// we do the firefox ci *only* on core because we're only really interested in IO here
addCommandAlias("ciFirefox", "; set Global / useFirefoxEnv := true; project rootJS; headerCheck; scalafmtCheck; clean; coreJS/test; set Global / useFirefoxEnv := false")

addCommandAlias("prePR", "; root/clean; +root/scalafmtAll; +root/headerCreate")

lazy val root = project.in(file("."))
  .aggregate(rootJVM, rootJS)
  .enablePlugins(NoPublishPlugin)

lazy val rootJVM = project
  .aggregate(kernel.jvm, testkit.jvm, laws.jvm, core.jvm, std.jvm, example.jvm, benchmarks)
  .enablePlugins(NoPublishPlugin)

lazy val rootJS = project
  .aggregate(kernel.js, testkit.js, laws.js, core.js, std.js, example.js)
  .enablePlugins(NoPublishPlugin)

/**
 * The core abstractions and syntax. This is the most general definition of Cats Effect,
 * without any concrete implementations. This is the "batteries not included" dependency.
 */
lazy val kernel = crossProject(JSPlatform, JVMPlatform).in(file("kernel"))
  .settings(
    name := "cats-effect-kernel",
    libraryDependencies += "org.specs2" %%% "specs2-core" % Specs2Version % Test)
  .settings(dottyLibrarySettings)
  .settings(libraryDependencies += "org.typelevel" %%% "cats-core" % CatsVersion)

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
      "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion,
      "org.typelevel"  %%% "coop"       % "1.0.0-M2"))

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
      "org.typelevel" %%% "discipline-specs2" % DisciplineVersion % Test))

/**
 * Concrete, production-grade implementations of the abstractions. Or, more
 * simply-put: IO and Resource. Also contains some general datatypes built
 * on top of IO which are useful in their own right, as well as some utilities
 * (such as IOApp). This is the "batteries included" dependency.
 */
lazy val core = crossProject(JSPlatform, JVMPlatform).in(file("core"))
  .dependsOn(kernel, std, laws % Test, testkit % Test)
  .settings(
    name := "cats-effect",

    libraryDependencies ++= Seq(
      "org.typelevel" %%% "discipline-specs2" % DisciplineVersion % Test,
      "org.typelevel" %%% "cats-kernel-laws"  % CatsVersion       % Test))
  .jvmSettings(
    Test / fork := true,
    Test / javaOptions += s"-Dsbt.classpath=${(Test / fullClasspath).value.map(_.data.getAbsolutePath).mkString(File.pathSeparator)}",
    javacOptions := javacOptions.value.filterNot(_ == "-Werror")
  )

/**
 * Implementations lof standard functionality (e.g. Semaphore, Console, Queue)
 * purely in terms of the typeclasses, with no dependency on IO. In most cases,
 * the *tests* for these implementations will require IO, and thus those tests
 * will be located within the core project.
 */
lazy val std = crossProject(JSPlatform, JVMPlatform).in(file("std"))
  .dependsOn(kernel)
  .settings(
    name := "cats-effect-std",

    libraryDependencies += {
      if (isDotty.value)
        ("org.specs2" %%% "specs2-scalacheck" % Specs2Version % Test)
          .withDottyCompat(scalaVersion.value)
          .exclude("org.scalacheck", "scalacheck_2.13")
          .exclude("org.scalacheck", "scalacheck_sjs1_2.13")
      else
        "org.specs2" %%% "specs2-scalacheck" % Specs2Version % Test
    },

    libraryDependencies += "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion % Test)

/**
 * A trivial pair of trivial example apps primarily used to show that IOApp
 * works as a practical runtime on both target platforms.
 */
lazy val example = crossProject(JSPlatform, JVMPlatform).in(file("example"))
  .dependsOn(core)
  .enablePlugins(NoPublishPlugin)
  .settings(name := "cats-effect-example")
  .jsSettings(scalaJSUseMainModuleInitializer := true)

/**
 * JMH benchmarks for IO and other things.
 */
lazy val benchmarks = project.in(file("benchmarks"))
  .dependsOn(core.jvm)
  .settings(name := "cats-effect-benchmarks")
  .enablePlugins(NoPublishPlugin, JmhPlugin)

lazy val docs = project.in(file("site-docs"))
  .dependsOn(core.jvm)
  .enablePlugins(MdocPlugin)
