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

import java.io.File
import java.util.concurrent.TimeUnit

import com.typesafe.tools.mima.core._
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.firefox.FirefoxOptions
import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.jsenv.selenium.SeleniumJSEnv

import JSEnv._

ThisBuild / baseVersion := "3.3"

ThisBuild / organization := "org.typelevel"
ThisBuild / organizationName := "Typelevel"

ThisBuild / startYear := Some(2020)
ThisBuild / endYear := Some(2021)

ThisBuild / developers := List(
  Developer(
    "djspiewak",
    "Daniel Spiewak",
    "djspiewak@gmail.com",
    url("https://github.com/djspiewak")),
  Developer(
    "SystemFw",
    "Fabio Labella",
    "fabio.labella2@gmail.com",
    url("https://github.com/SystemFw")),
  Developer(
    "RaasAhsan",
    "Raas Ahsan",
    "raas.ahsan@gmail.com",
    url("https://github.com/RaasAhsan")),
  Developer(
    "TimWSpence",
    "Tim Spence",
    "timothywspence@gmail.com",
    url("https://github.com/TimWSpence")),
  Developer(
    "kubukoz",
    "Jakub Kozłowski",
    "kubukoz@gmail.com",
    url("https://github.com/kubukoz")),
  Developer(
    "mpilquist",
    "Michael Pilquist",
    "mpilquist@gmail.com",
    url("https://github.com/mpilquist")),
  Developer(
    "vasilmkd",
    "Vasil Vasilev",
    "vasil@vasilev.io",
    url("https://github.com/vasilmkd")),
  Developer(
    "bplommer",
    "Ben Plommer",
    "ben.plommer@gmail.com",
    url("https://github.com/bplommer")),
  Developer(
    "wemrysi",
    "Emrys Ingersoll",
    "ingersoll@gmail.com",
    url("https://github.com/wemrysi")),
  Developer(
    "armanbilge",
    "Arman Bilge",
    "armanbilge@gmail.com",
    url("https://github.com/armanbilge")),
  Developer(
    "gvolpe",
    "Gabriel Volpe",
    "volpegabriel@gmail.com",
    url("https://github.com/gvolpe"))
)

val PrimaryOS = "ubuntu-latest"
val Windows = "windows-latest"

val ScalaJSJava = "adoptium@8"
val Scala213 = "2.13.7"
val Scala3 = "3.0.2"

ThisBuild / crossScalaVersions := Seq(Scala3, "2.12.15", Scala213)

ThisBuild / githubWorkflowUseSbtThinClient := false
ThisBuild / githubWorkflowTargetBranches := Seq("series/3.*")

val LTSJava = "adoptium@11"
val LatestJava = "adoptium@17"
val GraalVM = "graalvm-ce-java11@21.3"

ThisBuild / githubWorkflowJavaVersions := Seq(ScalaJSJava, LTSJava, LatestJava, GraalVM)
ThisBuild / githubWorkflowEnv += ("JABBA_INDEX" -> "https://github.com/typelevel/jdk-index/raw/main/index.json")
ThisBuild / githubWorkflowOSes := Seq(PrimaryOS, Windows)

ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Use(
    UseRef.Public("actions", "setup-node", "v2.4.0"),
    name = Some("Setup NodeJS v14 LTS"),
    params = Map("node-version" -> "14"),
    cond = Some("matrix.ci == 'ciJS'")
  ),
  WorkflowStep.Run(
    List("npm install"),
    name = Some("Install jsdom and source-map-support"),
    cond = Some("matrix.ci == 'ciJS'")
  )
)

ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("${{ matrix.ci }}")),
  WorkflowStep.Sbt(
    List("docs/mdoc"),
    cond = Some(
      s"(matrix.scala == '$Scala213' || matrix.scala == '$Scala3') && matrix.ci == 'ciJVM'")),
  WorkflowStep.Run(
    List("example/test-jvm.sh ${{ matrix.scala }}"),
    name = Some("Test Example JVM App Within Sbt"),
    cond = Some(s"matrix.ci == 'ciJVM' && matrix.os == '$PrimaryOS'")
  ),
  WorkflowStep.Run(
    List("example/test-js.sh ${{ matrix.scala }}"),
    name = Some("Test Example JavaScript App Using Node"),
    cond = Some(s"matrix.ci == 'ciJS' && matrix.os == '$PrimaryOS'")
  ),
  WorkflowStep.Run(
    List("cd scalafix", "sbt test"),
    name = Some("Scalafix tests"),
    cond =
      Some(s"matrix.scala == '$Scala213' && matrix.ci == 'ciJVM' && matrix.os == '$PrimaryOS'")
  )
)

val ciVariants = CI.AllCIs.map(_.command)
val jsCiVariants = CI.AllJSCIs.map(_.command)
ThisBuild / githubWorkflowBuildMatrixAdditions += "ci" -> ciVariants

ThisBuild / githubWorkflowBuildMatrixExclusions ++= {
  val windowsScalaFilters =
    (ThisBuild / githubWorkflowScalaVersions).value.filterNot(Set(Scala213)).map { scala =>
      MatrixExclude(Map("os" -> Windows, "scala" -> scala))
    }

  jsCiVariants.flatMap { ci =>
    val javaFilters =
      (ThisBuild / githubWorkflowJavaVersions).value.filterNot(Set(ScalaJSJava)).map { java =>
        MatrixExclude(Map("ci" -> ci, "java" -> java))
      }

    javaFilters ++ windowsScalaFilters :+ MatrixExclude(Map("os" -> Windows, "ci" -> ci))
  }
}

lazy val useJSEnv =
  settingKey[JSEnv]("Use Node.js or a headless browser for running Scala.js tests")
Global / useJSEnv := NodeJS

ThisBuild / Test / jsEnv := {
  useJSEnv.value match {
    case NodeJS => new NodeJSEnv(NodeJSEnv.Config().withSourceMap(true))
    case Firefox =>
      val options = new FirefoxOptions()
      options.setHeadless(true)
      new SeleniumJSEnv(options)
    case Chrome =>
      val options = new ChromeOptions()
      options.setHeadless(true)
      new SeleniumJSEnv(options)
  }
}

ThisBuild / homepage := Some(url("https://github.com/typelevel/cats-effect"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/typelevel/cats-effect"),
    "git@github.com:typelevel/cats-effect.git"))

ThisBuild / apiURL := Some(url("https://typelevel.org/cats-effect/api/3.x/"))

ThisBuild / autoAPIMappings := true

val CatsVersion = "2.6.1"
val Specs2Version = "4.13.0"
val ScalaCheckVersion = "1.15.4"
val DisciplineVersion = "1.2.4"
val CoopVersion = "1.1.1"

val MacrotaskExecutorVersion = "1.0.0"

replaceCommandAlias("ci", CI.AllCIs.map(_.toString).mkString)

addCommandAlias(CI.JVM.command, CI.JVM.toString)
addCommandAlias(CI.JS.command, CI.JS.toString)
addCommandAlias(CI.Firefox.command, CI.Firefox.toString)
addCommandAlias(CI.Chrome.command, CI.Chrome.toString)

addCommandAlias("prePR", "; root/clean; scalafmtSbt; +root/scalafmtAll; +root/headerCreate")

val jsProjects: Seq[ProjectReference] =
  Seq(kernel.js, kernelTestkit.js, laws.js, core.js, testkit.js, tests.js, std.js, example.js)

val undocumentedRefs =
  jsProjects ++ Seq[ProjectReference](benchmarks, example.jvm)

lazy val root = project
  .in(file("."))
  .aggregate(rootJVM, rootJS)
  .enablePlugins(NoPublishPlugin)
  .enablePlugins(ScalaUnidocPlugin)
  .settings(
    ScalaUnidoc / unidoc / unidocProjectFilter := {
      undocumentedRefs.foldLeft(inAnyProject)((acc, a) => acc -- inProjects(a))
    }
  )

lazy val rootJVM = project
  .aggregate(
    kernel.jvm,
    kernelTestkit.jvm,
    laws.jvm,
    core.jvm,
    testkit.jvm,
    tests.jvm,
    std.jvm,
    example.jvm,
    benchmarks)
  .enablePlugins(NoPublishPlugin)

lazy val rootJS = project.aggregate(jsProjects: _*).enablePlugins(NoPublishPlugin)

/**
 * The core abstractions and syntax. This is the most general definition of Cats Effect, without
 * any concrete implementations. This is the "batteries not included" dependency.
 */
lazy val kernel = crossProject(JSPlatform, JVMPlatform)
  .in(file("kernel"))
  .settings(
    name := "cats-effect-kernel",
    libraryDependencies += "org.typelevel" %%% "cats-core" % CatsVersion)
  .jvmSettings(libraryDependencies += {
    if (isDotty.value)
      ("org.specs2" %%% "specs2-core" % Specs2Version % Test).cross(CrossVersion.for3Use2_13)
    else
      "org.specs2" %%% "specs2-core" % Specs2Version % Test
  })
  .jsSettings(
    libraryDependencies += {
      if (isDotty.value)
        ("org.specs2" %%% "specs2-core" % Specs2Version % Test)
          .cross(CrossVersion.for3Use2_13)
          .exclude("org.scala-js", "scala-js-macrotask-executor_sjs1_2.13")
      else
        "org.specs2" %%% "specs2-core" % Specs2Version % Test
    },
    libraryDependencies += "org.scala-js" %%% "scala-js-macrotask-executor" % MacrotaskExecutorVersion % Test
  )

/**
 * Reference implementations (including a pure ConcurrentBracket), generic ScalaCheck
 * generators, and useful tools for testing code written against Cats Effect.
 */
lazy val kernelTestkit = crossProject(JSPlatform, JVMPlatform)
  .in(file("kernel-testkit"))
  .dependsOn(kernel)
  .settings(
    name := "cats-effect-kernel-testkit",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-free" % CatsVersion,
      "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion,
      "org.typelevel" %%% "coop" % CoopVersion),
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.kernel.testkit.TestContext.this"))
  )

/**
 * The laws which constrain the abstractions. This is split from kernel to avoid jar file and
 * dependency issues. As a consequence of this split, some things which are defined in
 * kernelTestkit are *tested* in the Test scope of this project.
 */
lazy val laws = crossProject(JSPlatform, JVMPlatform)
  .in(file("laws"))
  .dependsOn(kernel, kernelTestkit % Test)
  .settings(
    name := "cats-effect-laws",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-laws" % CatsVersion,
      "org.typelevel" %%% "discipline-specs2" % DisciplineVersion % Test)
  )

/**
 * Concrete, production-grade implementations of the abstractions. Or, more simply-put: IO. Also
 * contains some general datatypes built on top of IO which are useful in their own right, as
 * well as some utilities (such as IOApp). This is the "batteries included" dependency.
 */
lazy val core = crossProject(JSPlatform, JVMPlatform)
  .in(file("core"))
  .dependsOn(kernel, std)
  .settings(
    name := "cats-effect",
    javafmtOnCompile := false,
    mimaBinaryIssueFilters ++= Seq(
      // introduced by #1837, removal of package private class
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.AsyncPropagateCancelation"),
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.AsyncPropagateCancelation$"),
      // introduced by #1913, striped fiber callback hashtable, changes to package private code
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.FiberErrorHashtable"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "cats.effect.unsafe.IORuntime.fiberErrorCbs"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("cats.effect.unsafe.IORuntime.this"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "cats.effect.unsafe.IORuntime.<init>$default$6"),
      // introduced by #1928, wake up a worker thread before spawning a helper thread when blocking
      // changes to `cats.effect.unsafe` package private code
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "cats.effect.unsafe.WorkStealingThreadPool.notifyParked"),
      // introduced by #2041, Rewrite and improve `ThreadSafeHashtable`
      // changes to `cats.effect.unsafe` package private code
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.ThreadSafeHashtable.hashtable"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.ThreadSafeHashtable.hashtable_="),
      // introduced by #2051, Tracing
      // changes to package private code
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#Blocking.apply"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#Blocking.copy"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#Blocking.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#Delay.apply"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#Delay.copy"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#Delay.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#FlatMap.apply"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#FlatMap.copy"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#FlatMap.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.IO#HandleErrorWith.apply"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#HandleErrorWith.copy"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#HandleErrorWith.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#Map.apply"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#Map.copy"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#Map.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#Uncancelable.apply"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#Uncancelable.copy"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#Uncancelable.this"),
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.SyncIO$Delay$"),
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.SyncIO$Delay"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#IOCont.apply"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#IOCont.copy"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO#IOCont.this"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "cats.effect.unsafe.IORuntimeCompanionPlatform.installGlobal"),
      // introduced by #2207, tracing for js
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "cats.effect.tracing.Tracing.calculateTracingEvent"),
      ProblemFilters.exclude[Problem]("cats.effect.ByteStack.*"),
      // introduced by #2254, Check `WorkerThread` ownership before scheduling
      // changes to `cats.effect.unsafe` package private code
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.WorkStealingThreadPool.executeFiber"),
      // introduced by #2256, Hide the package private constructor for `IORuntime`
      // changes to `cats.effect.unsafe` package private code
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.unsafe.IORuntime.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.IORuntime.<init>$default$6"),
      // introduced by #2312, Address issues with the blocking mechanism of the thread pool
      // changes to `cats.effect.unsafe` package private code
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.unsafe.LocalQueue.drain"),
      // introduced by #2345, Overflow and batched queue unification
      // changes to `cats.effect.unsafe` package private code
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.HelperThread.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.LocalQueue.enqueue"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.WorkerThread.this"),
      // introduced by #2383, Revised `LocalQueue` metrics
      // changes to `cats.effect.unsafe` package private code
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.LocalQueue.getOverflowSpilloverCount"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.LocalQueue.getBatchedSpilloverCount"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.unsafe.LocalQueue.drain"),
      // introduced by #2361, Bye bye helper thread
      // changes to `cats.effect.unsafe` package private code
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.HelperThread"),
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.LocalQueue$"),
      // introduced by #2434, Initialize tracing buffer if needed
      // changes to `cats.effect` package private code
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.ArrayStack.copy"),
      // introduced by #2453, Masking without an `initMask` field
      // changes to `cats.effect` package private code
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.IO#Uncancelable#UnmaskRunLoop.apply"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.IO#Uncancelable#UnmaskRunLoop.copy"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.IO#Uncancelable#UnmaskRunLoop.this"),
      // introduced by #2510, Fix weak bag for the blocking mechanism
      // changes to `cats.effect.unsafe` package private code
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "cats.effect.unsafe.WorkerThread.this"),
      // introduced by #2513, Implement the active fiber tracking mechanism
      // changes to `cats.effect.unsafe` package private code
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.LocalQueue.dequeue"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.LocalQueue.enqueueBatch"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.LocalQueue.stealInto")
    )
  )
  .jvmSettings(
    javacOptions ++= Seq("-source", "1.8", "-target", "1.8")
  )
  .jsSettings(
    libraryDependencies += "org.scala-js" %%% "scala-js-macrotask-executor" % MacrotaskExecutorVersion)

/**
 * Test support for the core project, providing various helpful instances like ScalaCheck
 * generators for IO and SyncIO.
 */
lazy val testkit = crossProject(JSPlatform, JVMPlatform)
  .in(file("testkit"))
  .dependsOn(core, kernelTestkit)
  .settings(
    name := "cats-effect-testkit",
    libraryDependencies += "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion)
  .jvmSettings(libraryDependencies += {
    if (isDotty.value)
      ("org.specs2" %%% "specs2-core" % Specs2Version % Test).cross(CrossVersion.for3Use2_13)
    else
      "org.specs2" %%% "specs2-core" % Specs2Version % Test
  })
  .jsSettings(libraryDependencies += {
    if (isDotty.value)
      ("org.specs2" %%% "specs2-core" % Specs2Version % Test)
        .cross(CrossVersion.for3Use2_13)
        .exclude("org.scala-js", "scala-js-macrotask-executor_sjs1_2.13")
    else
      "org.specs2" %%% "specs2-core" % Specs2Version % Test
  })

/**
 * Unit tests for the core project, utilizing the support provided by testkit.
 */
lazy val tests = crossProject(JSPlatform, JVMPlatform)
  .in(file("tests"))
  .dependsOn(laws % Test, kernelTestkit % Test, testkit % Test)
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "cats-effect-tests",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "discipline-specs2" % DisciplineVersion % Test,
      "org.typelevel" %%% "cats-kernel-laws" % CatsVersion % Test)
  )
  .jvmSettings(
    Test / fork := true,
    Test / javaOptions += s"-Dsbt.classpath=${(Test / fullClasspath).value.map(_.data.getAbsolutePath).mkString(File.pathSeparator)}")

/**
 * Implementations lof standard functionality (e.g. Semaphore, Console, Queue) purely in terms
 * of the typeclasses, with no dependency on IO. In most cases, the *tests* for these
 * implementations will require IO, and thus those tests will be located within the core
 * project.
 */
lazy val std = crossProject(JSPlatform, JVMPlatform)
  .in(file("std"))
  .dependsOn(kernel)
  .settings(
    name := "cats-effect-std",
    libraryDependencies += "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion % Test)
  .jvmSettings(libraryDependencies += {
    if (isDotty.value)
      ("org.specs2" %%% "specs2-scalacheck" % Specs2Version % Test)
        .cross(CrossVersion.for3Use2_13)
        .exclude("org.scalacheck", "scalacheck_2.13")
        .exclude("org.scalacheck", "scalacheck_sjs1_2.13")
    else
      "org.specs2" %%% "specs2-scalacheck" % Specs2Version % Test
  })
  .jsSettings(
    libraryDependencies += {
      if (isDotty.value)
        ("org.specs2" %%% "specs2-scalacheck" % Specs2Version % Test)
          .cross(CrossVersion.for3Use2_13)
          .exclude("org.scala-js", "scala-js-macrotask-executor_sjs1_2.13")
          .exclude("org.scalacheck", "scalacheck_2.13")
          .exclude("org.scalacheck", "scalacheck_sjs1_2.13")
      else
        "org.specs2" %%% "specs2-scalacheck" % Specs2Version % Test
    },
    libraryDependencies += "org.scala-js" %%% "scala-js-macrotask-executor" % MacrotaskExecutorVersion % Test
  )

/**
 * A trivial pair of trivial example apps primarily used to show that IOApp works as a practical
 * runtime on both target platforms.
 */
lazy val example = crossProject(JSPlatform, JVMPlatform)
  .in(file("example"))
  .dependsOn(core)
  .enablePlugins(NoPublishPlugin)
  .settings(name := "cats-effect-example")
  .jsSettings(scalaJSUseMainModuleInitializer := true)

/**
 * JMH benchmarks for IO and other things.
 */
lazy val benchmarks = project
  .in(file("benchmarks"))
  .dependsOn(core.jvm)
  .settings(
    name := "cats-effect-benchmarks",
    javaOptions ++= Seq(
      "-Dcats.effect.tracing.mode=none",
      "-Dcats.effect.tracing.exceptions.enhanced=false"))
  .enablePlugins(NoPublishPlugin, JmhPlugin)

lazy val docs = project.in(file("site-docs")).dependsOn(core.jvm).enablePlugins(MdocPlugin)
