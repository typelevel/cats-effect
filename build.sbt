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

import java.io.File
import java.util.concurrent.TimeUnit

import com.typesafe.tools.mima.core._
import com.github.sbt.git.SbtGit.GitKeys._
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.firefox.FirefoxOptions
import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.jsenv.selenium.SeleniumJSEnv
import sbtcrossproject.CrossProject

import JSEnv._

// sbt-git workarounds
ThisBuild / useConsoleForROGit := true

ThisBuild / git.gitUncommittedChanges := {
  import scala.sys.process._
  import scala.util.Try

  Try("git status -s".!!.trim.length > 0).getOrElse(true)
}

ThisBuild / tlBaseVersion := "3.3"
ThisBuild / tlUntaggedAreSnapshots := false

ThisBuild / organization := "org.typelevel"
ThisBuild / organizationName := "Typelevel"
ThisBuild / tlSonatypeUseLegacyHost := false

ThisBuild / startYear := Some(2020)

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
val MacOS = "macos-latest"

val Scala213 = "2.13.7"
val Scala3 = "3.0.2"

ThisBuild / crossScalaVersions := Seq(Scala3, "2.12.15", Scala213)
ThisBuild / tlVersionIntroduced := Map("3" -> "3.1.1")
ThisBuild / tlJdkRelease := Some(8)

ThisBuild / githubWorkflowTargetBranches := Seq("series/3.*")
ThisBuild / tlCiReleaseTags := false
ThisBuild / tlCiReleaseBranches := Nil

val OldGuardJava = JavaSpec.temurin("8")
val LTSJava = JavaSpec.temurin("11")
val LatestJava = JavaSpec.temurin("17")
val ScalaJSJava = OldGuardJava
val GraalVM = JavaSpec.graalvm("11")

ThisBuild / githubWorkflowJavaVersions := Seq(OldGuardJava, LTSJava, LatestJava, GraalVM)
ThisBuild / githubWorkflowOSes := Seq(PrimaryOS, Windows, MacOS)

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

ThisBuild / githubWorkflowBuildMatrixExclusions := {
  val scalaJavaFilters = for {
    scala <- (ThisBuild / githubWorkflowScalaVersions).value.filterNot(Set(Scala213))
    java <- (ThisBuild / githubWorkflowJavaVersions).value.filterNot(Set(OldGuardJava))
  } yield MatrixExclude(Map("scala" -> scala, "java" -> java.render))

  val windowsAndMacScalaFilters =
    (ThisBuild / githubWorkflowScalaVersions).value.filterNot(Set(Scala213)).flatMap { scala =>
      Seq(
        MatrixExclude(Map("os" -> Windows, "scala" -> scala)),
        MatrixExclude(Map("os" -> MacOS, "scala" -> scala)))
    }

  val jsScalaFilters = for {
    scala <- (ThisBuild / githubWorkflowScalaVersions).value.filterNot(Set(Scala213))
    ci <- jsCiVariants.tail
  } yield MatrixExclude(Map("ci" -> ci, "scala" -> scala))

  val jsJavaAndOSFilters = jsCiVariants.flatMap { ci =>
    val javaFilters =
      (ThisBuild / githubWorkflowJavaVersions).value.filterNot(Set(ScalaJSJava)).map { java =>
        MatrixExclude(Map("ci" -> ci, "java" -> java.render))
      }

    javaFilters ++ Seq(
      MatrixExclude(Map("os" -> Windows, "ci" -> ci)),
      MatrixExclude(Map("os" -> MacOS, "ci" -> ci)))
  }

  // Nice-to-haves but unreliable in CI
  val flakyFilters = Seq(
    MatrixExclude(Map("os" -> Windows, "java" -> GraalVM.render))
  )

  scalaJavaFilters ++ windowsAndMacScalaFilters ++ jsScalaFilters ++ jsJavaAndOSFilters ++ flakyFilters
}

lazy val useJSEnv =
  settingKey[JSEnv]("Use Node.js or a headless browser for running Scala.js tests")
Global / useJSEnv := NodeJS

lazy val testJSIOApp =
  settingKey[Boolean]("Whether to test JVM (false) or Node.js (true) in IOAppSpec")
Global / testJSIOApp := false

ThisBuild / jsEnv := {
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

val CatsVersion = "2.7.0"
val Specs2Version = "4.13.1"
val ScalaCheckVersion = "1.15.4"
val DisciplineVersion = "1.2.5"
val CoopVersion = "1.1.1"

val MacrotaskExecutorVersion = "1.0.0"

tlReplaceCommandAlias("ci", CI.AllCIs.map(_.toString).mkString)
addCommandAlias("release", "tlRelease")

addCommandAlias(CI.JVM.command, CI.JVM.toString)
addCommandAlias(CI.JS.command, CI.JS.toString)
addCommandAlias(CI.Firefox.command, CI.Firefox.toString)
addCommandAlias(CI.Chrome.command, CI.Chrome.toString)

addCommandAlias("prePR", "; root/clean; scalafmtSbt; +root/scalafmtAll; +root/headerCreate")

val jsProjects: Seq[ProjectReference] =
  Seq(kernel.js, kernelTestkit.js, laws.js, core.js, testkit.js, testsJS, std.js, example.js)

val undocumentedRefs =
  jsProjects ++ Seq[ProjectReference](benchmarks, example.jvm, tests.jvm, tests.js)

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
    testsJVM,
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
  .settings(
    libraryDependencies += ("org.specs2" %%% "specs2-core" % Specs2Version % Test)
      .cross(CrossVersion.for3Use2_13)
      .exclude("org.scala-js", "scala-js-macrotask-executor_sjs1_2.13")
  )
  .jsSettings(
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
        "cats.effect.unsafe.LocalQueue.stealInto"),
      // introduced by #2673, Cross platform weak bag implementation
      // changes to `cats.effect.unsafe` package private code
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.WorkerThread.monitor"),
      // introduced by #2769, Simplify the transfer of WorkerThread data structures when blocking
      // changes to `cats.effect.unsafe` package private code
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.WorkerThread$"),
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.WorkerThread$Data"),
      // introduced by #2844, Thread local fallback weak bag
      // changes to `cats.effect.unsafe` package private code
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.SynchronizedWeakBag"),
      // introduced by #2868
      // added signaling from CallbackStack to indicate successful invocation
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.CallbackStack.apply")
    ) ++ {
      if (tlIsScala3.value) {
        // Scala 3 specific exclusions
        Seq(
          // introduced by #2769, Simplify the transfer of WorkerThread data structures when blocking
          // changes to `cats.effect.unsafe` package private code
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.WorkStealingThreadPool.localQueuesForwarder"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.WorkerThread.NullData"),
          // introduced by #2857, when we properly turned on MiMa for Scala 3
          ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IOFiber.this"),
          ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IOFiber.cancel_="),
          ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IOFiber.join_="),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.IOFiberPlatform.interruptibleImpl"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.WorkStealingThreadPool.stealFromOtherWorkerThread"),
          ProblemFilters.exclude[FinalClassProblem](
            "cats.effect.unsafe.metrics.LocalQueueSampler"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.metrics.LocalQueueSampler.getOverflowSpilloverCount"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.metrics.LocalQueueSampler.getBatchedSpilloverCount"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.metrics.LocalQueueSamplerMBean.getOverflowSpilloverCount"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.metrics.LocalQueueSamplerMBean.getBatchedSpilloverCount"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.metrics.LocalQueueSamplerMBean.getTotalSpilloverCount"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.FiberMonitor.weakMapToSet"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.FiberMonitor.monitorSuspended"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.FiberMonitor.weakMapToSet"),
          ProblemFilters.exclude[IncompatibleMethTypeProblem](
            "cats.effect.unsafe.IORuntime.installGlobal"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.LocalQueue.EmptyDrain"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.WorkStealingThreadPool.notifyHelper"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.WorkStealingThreadPool.transitionHelperToParked"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.WorkStealingThreadPool.removeParkedHelper")
        )
      } else Seq()
    }
  )
  .jsSettings(
    libraryDependencies += "org.scala-js" %%% "scala-js-macrotask-executor" % MacrotaskExecutorVersion,
    mimaBinaryIssueFilters ++= {
      Seq(
        // introduced by #2857, when we properly turned on MiMa for Scala.js
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.unsafe.ES2021FiberMonitor.monitorSuspended"),
        ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.IterableWeakMap"),
        ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.IterableWeakMap$"),
        ProblemFilters.exclude[MissingClassProblem](
          "cats.effect.unsafe.IterableWeakMap$Finalizer"),
        ProblemFilters.exclude[MissingClassProblem](
          "cats.effect.unsafe.IterableWeakMap$Finalizer$"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.unsafe.NoOpFiberMonitor.monitorSuspended"),
        ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.WeakMap"),
        ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO.interruptible"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.IOFiberConstants.EvalOnR"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.IOFiberConstants.AfterBlockingFailedR"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.IOFiberConstants.AfterBlockingSuccessfulR"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.IOFiberConstants.ChildMaskOffset"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.IOFiberConstants.ChildMaskOffset"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.IOFiberConstants.AfterBlockingSuccessfulR"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.IOFiberConstants.AfterBlockingFailedR"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.IOFiberConstants.EvalOnR"),
        ProblemFilters.exclude[MissingClassProblem](
          "cats.effect.unsafe.PolyfillExecutionContext"),
        ProblemFilters.exclude[MissingClassProblem](
          "cats.effect.unsafe.PolyfillExecutionContext$"),
        ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.WorkerThread")
      )
    },
    mimaBinaryIssueFilters ++= {
      if (tlIsScala3.value) {
        Seq(
          // introduced by #2857, when we properly turned on MiMa for Scala.js and Scala 3
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.tracing.Tracing.bumpVersion"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.tracing.Tracing.castEntry"),
          ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.tracing.Tracing.get"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.tracing.Tracing.match"),
          ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.tracing.Tracing.put"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.tracing.Tracing.remove"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.tracing.Tracing.version"),
          ProblemFilters.exclude[MissingTypesProblem]("cats.effect.tracing.Tracing$"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.tracing.Tracing.computeValue"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.tracing.TracingConstants.enhancedExceptions"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.tracing.TracingConstants.traceBufferLogSize"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.tracing.TracingConstants.traceBufferLogSize"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.tracing.TracingConstants.enhancedExceptions"),
          ProblemFilters.exclude[ReversedMissingMethodProblem](
            "cats.effect.unsafe.WorkStealingThreadPool.canExecuteBlockingCode"),
          ProblemFilters.exclude[ReversedMissingMethodProblem](
            "cats.effect.unsafe.FiberMonitor.monitorSuspended")
        )
      } else Seq()
    }
  )

/**
 * Test support for the core project, providing various helpful instances like ScalaCheck
 * generators for IO and SyncIO.
 */
lazy val testkit = crossProject(JSPlatform, JVMPlatform)
  .in(file("testkit"))
  .dependsOn(core, kernelTestkit)
  .settings(
    name := "cats-effect-testkit",
    libraryDependencies ++= Seq(
      "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion,
      ("org.specs2" %%% "specs2-core" % Specs2Version % Test)
        .cross(CrossVersion.for3Use2_13)
        .exclude("org.scala-js", "scala-js-macrotask-executor_sjs1_2.13")
    )
  )

/**
 * Unit tests for the core project, utilizing the support provided by testkit.
 */
lazy val tests: CrossProject = crossProject(JSPlatform, JVMPlatform)
  .in(file("tests"))
  .dependsOn(core, laws % Test, kernelTestkit % Test, testkit % Test)
  .enablePlugins(BuildInfoPlugin, NoPublishPlugin)
  .settings(
    name := "cats-effect-tests",
    libraryDependencies ++= Seq(
      "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion,
      ("org.specs2" %%% "specs2-scalacheck" % Specs2Version % Test)
        .cross(CrossVersion.for3Use2_13)
        .exclude("org.scala-js", "scala-js-macrotask-executor_sjs1_2.13")
        .exclude("org.scalacheck", "scalacheck_2.13")
        .exclude("org.scalacheck", "scalacheck_sjs1_2.13"),
      "org.typelevel" %%% "discipline-specs2" % DisciplineVersion % Test,
      "org.typelevel" %%% "cats-kernel-laws" % CatsVersion % Test
    ),
    buildInfoPackage := "catseffect"
  )
  .jsSettings(
    Compile / scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("catseffect.examples.JSRunner"),
    // The default configured mapSourceURI is used for trace filtering
    scalacOptions ~= { _.filterNot(_.startsWith("-P:scalajs:mapSourceURI")) }
  )
  .jvmSettings(
    Test / fork := true,
    Test / javaOptions += s"-Dsbt.classpath=${(Test / fullClasspath).value.map(_.data.getAbsolutePath).mkString(File.pathSeparator)}"
  )

lazy val testsJS = tests.js
lazy val testsJVM = tests
  .jvm
  .enablePlugins(BuildInfoPlugin)
  .settings(
    Test / compile := {
      if (testJSIOApp.value)
        (Test / compile).dependsOn(testsJS / Compile / fastOptJS).value
      else
        (Test / compile).value
    },
    buildInfoPackage := "cats.effect",
    buildInfoKeys += testJSIOApp,
    buildInfoKeys +=
      "jsRunner" -> (testsJS / Compile / fastOptJS / artifactPath).value
  )

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
    libraryDependencies ++= Seq(
      "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion % Test,
      ("org.specs2" %%% "specs2-scalacheck" % Specs2Version % Test)
        .cross(CrossVersion.for3Use2_13)
        .exclude("org.scala-js", "scala-js-macrotask-executor_sjs1_2.13")
        .exclude("org.scalacheck", "scalacheck_2.13")
        .exclude("org.scalacheck", "scalacheck_sjs1_2.13")
    )
  )
  .jsSettings(
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
