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
import org.openqa.selenium.firefox.{FirefoxOptions, FirefoxProfile}
import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.jsenv.selenium.SeleniumJSEnv
import sbtcrossproject.CrossProject

import JSEnv._

// sbt-git workarounds
ThisBuild / useConsoleForROGit := !Option(System.getenv("CI")).contains("true")

ThisBuild / git.gitUncommittedChanges := {
  if ((ThisBuild / githubIsWorkflowBuild).value) {
    git.gitUncommittedChanges.value
  } else {
    import scala.sys.process._
    import scala.util.Try

    Try("git status -s".!!.trim.length > 0).getOrElse(true)
  }
}

ThisBuild / tlBaseVersion := "3.5"
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

val Scala212 = "2.12.17"
val Scala213 = "2.13.10"
val Scala3 = "3.2.2"

ThisBuild / crossScalaVersions := Seq(Scala3, Scala212, Scala213)
ThisBuild / githubWorkflowScalaVersions := crossScalaVersions.value
ThisBuild / tlVersionIntroduced := Map("3" -> "3.1.1")
ThisBuild / tlJdkRelease := Some(8)

ThisBuild / githubWorkflowTargetBranches := Seq("series/3.*")
ThisBuild / tlCiReleaseTags := true
ThisBuild / tlCiReleaseBranches := Nil

ThisBuild / githubWorkflowArtifactDownloadExtraKeys += "ci"
ThisBuild / githubWorkflowPublishPreamble +=
  WorkflowStep.Use(
    UseRef.Public("typelevel", "await-cirrus", "main"),
    name = Some("Wait for Cirrus CI")
  )

val OldGuardJava = JavaSpec.temurin("8")
val LTSJava = JavaSpec.temurin("11")
val LatestJava = JavaSpec.temurin("17")
val ScalaJSJava = OldGuardJava
val ScalaNativeJava = OldGuardJava
val GraalVM = JavaSpec.graalvm("11")

ThisBuild / githubWorkflowJavaVersions := Seq(OldGuardJava, LTSJava, LatestJava, GraalVM)
ThisBuild / githubWorkflowOSes := Seq(PrimaryOS, Windows, MacOS)

ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Use(
    UseRef.Public("actions", "setup-node", "v3"),
    name = Some("Setup NodeJS v18 LTS"),
    params = Map("node-version" -> "18"),
    cond = Some("matrix.ci == 'ciJS'")
  ),
  WorkflowStep.Run(
    List("npm install"),
    name = Some("Install jsdom and source-map-support"),
    cond = Some("matrix.ci == 'ciJS'")
  ),
  WorkflowStep.Run(
    List("gu install native-image"),
    name = Some("Install GraalVM Native Image"),
    cond = Some(s"matrix.java == '${GraalVM.render}'")
  ),
  WorkflowStep.Use(
    UseRef.Public("al-cheb", "configure-pagefile-action", "v1.3"),
    name = Some("Configure Windows Pagefile"),
    params = Map("minimum-size" -> "2GB", "maximum-size" -> "8GB"),
    cond = Some(s"matrix.os == '$Windows'")
  )
)

ThisBuild / githubWorkflowBuild := Seq("JVM", "JS", "Native").map { platform =>
  WorkflowStep.Sbt(
    List(s"root${platform}/scalafixAll --check"),
    name = Some(s"Check that scalafix has been run on $platform"),
    cond = Some(
      s"matrix.ci == 'ci${platform}' && matrix.scala != '$Scala3' && matrix.java == '${OldGuardJava.render}' && matrix.os == '$PrimaryOS'"
    ) // windows has file lock issues due to shared sources
  )
} ++ Seq(
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
  WorkflowStep.Sbt(
    List("graalVMExample/nativeImage", "graalVMExample/nativeImageRun"),
    name = Some("Test GraalVM Native Image"),
    cond = Some(
      s"matrix.scala == '$Scala213' && matrix.java == '${GraalVM.render}' && matrix.os == '$PrimaryOS'")
  ),
  WorkflowStep.Run(
    List("example/test-native.sh ${{ matrix.scala }}"),
    name = Some("Test Example Native App Using Binary"),
    cond = Some(s"matrix.ci == 'ciNative' && matrix.os == '$PrimaryOS'")
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
    if !(scala == Scala3 && (java == LatestJava || java == GraalVM))
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

  val nativeJavaAndOSFilters = {
    val ci = CI.Native.command

    val javaFilters =
      (ThisBuild / githubWorkflowJavaVersions).value.filterNot(Set(ScalaNativeJava)).map {
        java => MatrixExclude(Map("ci" -> ci, "java" -> java.render))
      }

    javaFilters ++ Seq(
      MatrixExclude(Map("os" -> Windows, "ci" -> ci)),
      MatrixExclude(Map("os" -> MacOS, "ci" -> ci, "scala" -> Scala212)),
      // keep a native+2.13+macos job
      MatrixExclude(Map("os" -> MacOS, "ci" -> ci, "scala" -> Scala3))
    )
  }

  // Nice-to-haves but unreliable in CI
  val flakyFilters = Seq(
    MatrixExclude(Map("os" -> Windows, "java" -> GraalVM.render))
  )

  scalaJavaFilters ++ windowsAndMacScalaFilters ++ jsScalaFilters ++ jsJavaAndOSFilters ++ nativeJavaAndOSFilters ++ flakyFilters
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
      val profile = new FirefoxProfile()
      profile.setPreference("privacy.reduceTimerPrecision", false)
      val options = new FirefoxOptions()
      options.setProfile(profile)
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

val CatsVersion = "2.9.0"
val Specs2Version = "4.20.0"
val ScalaCheckVersion = "1.17.0"
val DisciplineVersion = "1.4.0"
val CoopVersion = "1.2.0"

val MacrotaskExecutorVersion = "1.1.1"

val ScalacCompatVersion = "0.1.0"

tlReplaceCommandAlias("ci", CI.AllCIs.map(_.toString).mkString)
addCommandAlias("release", "tlRelease")

addCommandAlias(CI.JVM.command, CI.JVM.toString)
addCommandAlias(CI.Native.command, CI.Native.toString)
addCommandAlias(CI.JS.command, CI.JS.toString)
addCommandAlias(CI.Firefox.command, CI.Firefox.toString)
addCommandAlias(CI.Chrome.command, CI.Chrome.toString)

tlReplaceCommandAlias(
  "prePR",
  "; root/clean; +root/headerCreate; root/scalafixAll; scalafmtSbt; +root/scalafmtAll")

val jsProjects: Seq[ProjectReference] =
  Seq(kernel.js, kernelTestkit.js, laws.js, core.js, testkit.js, testsJS, std.js, example.js)

val nativeProjects: Seq[ProjectReference] =
  Seq(
    kernel.native,
    kernelTestkit.native,
    laws.native,
    core.native,
    testkit.native,
    tests.native,
    std.native,
    example.native)

val undocumentedRefs =
  jsProjects ++ nativeProjects ++ Seq[ProjectReference](
    benchmarks,
    stressTests,
    example.jvm,
    graalVMExample,
    tests.jvm,
    tests.js)

lazy val root = project
  .in(file("."))
  .aggregate(rootJVM, rootJS, rootNative)
  .enablePlugins(NoPublishPlugin)
  .enablePlugins(ScalaUnidocPlugin)
  .settings(
    name := "cats-effect",
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
    graalVMExample,
    benchmarks,
    stressTests)
  .enablePlugins(NoPublishPlugin)

lazy val rootJS = project.aggregate(jsProjects: _*).enablePlugins(NoPublishPlugin)

lazy val rootNative = project.aggregate(nativeProjects: _*).enablePlugins(NoPublishPlugin)

/**
 * The core abstractions and syntax. This is the most general definition of Cats Effect, without
 * any concrete implementations. This is the "batteries not included" dependency.
 */
lazy val kernel = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("kernel"))
  .settings(
    name := "cats-effect-kernel",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % CatsVersion,
      "org.specs2" %%% "specs2-core" % Specs2Version % Test
    ),
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.kernel.Ref$SyncRef"),
      ProblemFilters.exclude[Problem]("cats.effect.kernel.GenConcurrent#Memoize*")
    )
  )
  .disablePlugins(JCStressPlugin)
  .jsSettings(
    libraryDependencies += "org.scala-js" %%% "scala-js-macrotask-executor" % MacrotaskExecutorVersion % Test
  )
  .nativeSettings(
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % "2.5.0"
  )

/**
 * Reference implementations (including a pure ConcurrentBracket), generic ScalaCheck
 * generators, and useful tools for testing code written against Cats Effect.
 */
lazy val kernelTestkit = crossProject(JSPlatform, JVMPlatform, NativePlatform)
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
        "cats.effect.kernel.testkit.TestContext.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.kernel.testkit.TestContext#State.execute"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.kernel.testkit.TestContext#State.scheduleOnce"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.kernel.testkit.TestContext#Task.apply"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.kernel.testkit.TestContext#Task.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.kernel.testkit.TestContext#Task.copy")
    )
  )
  .disablePlugins(JCStressPlugin)

/**
 * The laws which constrain the abstractions. This is split from kernel to avoid jar file and
 * dependency issues. As a consequence of this split, some things which are defined in
 * kernelTestkit are *tested* in the Test scope of this project.
 */
lazy val laws = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("laws"))
  .dependsOn(kernel, kernelTestkit % Test)
  .settings(
    name := "cats-effect-laws",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-laws" % CatsVersion,
      "org.typelevel" %%% "discipline-specs2" % DisciplineVersion % Test)
  )
  .disablePlugins(JCStressPlugin)

/**
 * Concrete, production-grade implementations of the abstractions. Or, more simply-put: IO. Also
 * contains some general datatypes built on top of IO which are useful in their own right, as
 * well as some utilities (such as IOApp). This is the "batteries included" dependency.
 */
lazy val core = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("core"))
  .dependsOn(kernel, std)
  .settings(
    name := "cats-effect",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "scalac-compat-annotation" % ScalacCompatVersion % CompileTime
    ),
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
      // introduced by #2873, The WSTP can run Futures just as fast as ExecutionContext.global
      // changes to `cats.effect.unsafe` package private code
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "cats.effect.unsafe.LocalQueue.bufferForwarder"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "cats.effect.unsafe.LocalQueue.dequeue"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "cats.effect.unsafe.LocalQueue.enqueue"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "cats.effect.unsafe.LocalQueue.enqueueBatch"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "cats.effect.unsafe.LocalQueue.stealInto"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "cats.effect.unsafe.WorkerThread.monitor"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "cats.effect.unsafe.WorkerThread.reschedule"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "cats.effect.unsafe.WorkerThread.schedule"),
      // introduced by #2868
      // added signaling from CallbackStack to indicate successful invocation
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.CallbackStack.apply"),
      // introduced by #2869
      // package-private method
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IO.unsafeRunFiber"),
      // introduced by #4248
      // changes to package private code
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.NonDaemonThreadLogger.isEnabled"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.NonDaemonThreadLogger.sleepIntervalMillis"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.NonDaemonThreadLogger.this"),
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.NonDaemonThreadLogger$"),
      // introduced by #3284
      // internal API change
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("cats.effect.CallbackStack.apply"),
      // introduced by #3324, which specialized CallbackStack for JS
      // internal API change
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.CallbackStack.clearCurrent"),
      // #3393, ContState is a private class:
      ProblemFilters.exclude[MissingTypesProblem]("cats.effect.ContState"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.ContState.result"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.ContState.result_="),
      // #3393 and #3464, IOFiberConstants is a (package) private class/object:
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.IOFiberConstants.ContStateInitial"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.IOFiberConstants.ContStateWaiting"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.IOFiberConstants.ContStateWinner"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.IOFiberConstants.ContStateResult"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.IOFiberConstants.ExecuteRunnableR"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("cats.effect.IOLocal.scope"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.IOFiberConstants.ContStateResult")
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
          // introduced by #2811, Support shutting down multiple runtimes
          // changes to `cats.effect.unsafe` package private code
          ProblemFilters.exclude[IncompatibleMethTypeProblem](
            "cats.effect.unsafe.ThreadSafeHashtable.put"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.IORuntime.apply"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.IORuntimeCompanionPlatform.apply"),
          ProblemFilters.exclude[IncompatibleMethTypeProblem](
            "cats.effect.unsafe.ThreadSafeHashtable.remove"),
          ProblemFilters.exclude[IncompatibleResultTypeProblem](
            "cats.effect.unsafe.ThreadSafeHashtable.unsafeHashtable"),
          ProblemFilters.exclude[IncompatibleResultTypeProblem](
            "cats.effect.unsafe.ThreadSafeHashtable.Tombstone"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.WorkStealingThreadPool.this"),
          // introduced by #2853, Configurable caching of blocking threads, properly
          // changes to `cats.effect.unsafe` package private code
          ProblemFilters.exclude[IncompatibleMethTypeProblem](
            "cats.effect.unsafe.WorkStealingThreadPool.this"),
          // introduced by #2873, The WSTP can run Futures just as fast as ExecutionContext.global
          // changes to `cats.effect.unsafe` package private code
          ProblemFilters.exclude[IncompatibleResultTypeProblem](
            "cats.effect.unsafe.WorkerThread.active"),
          ProblemFilters.exclude[IncompatibleMethTypeProblem](
            "cats.effect.unsafe.WorkerThread.active_="),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.WorkStealingThreadPool.rescheduleFiber"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.WorkStealingThreadPool.scheduleFiber"),
          ProblemFilters.exclude[IncompatibleResultTypeProblem](
            "cats.effect.unsafe.WorkStealingThreadPool.stealFromOtherWorkerThread"),
          ProblemFilters.exclude[ReversedMissingMethodProblem](
            "cats.effect.unsafe.WorkStealingThreadPool.reschedule"),
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
            "cats.effect.unsafe.WorkStealingThreadPool.removeParkedHelper"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.tracing.Tracing.bumpVersion"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.tracing.Tracing.castEntry"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.tracing.Tracing.match"),
          ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.tracing.Tracing.put"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.tracing.Tracing.version"),
          // introduced by #3012
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.WorkStealingThreadPool.this"),
          // annoying consequence of reverting #2473
          ProblemFilters.exclude[AbstractClassProblem]("cats.effect.ExitCode")
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
        ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.WorkerThread"),
        ProblemFilters.exclude[Problem]("cats.effect.IOFiberConstants.*"),
        ProblemFilters.exclude[Problem]("cats.effect.SyncIOConstants.*"),
        // introduced by #3196. Changes in an internal API.
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.unsafe.FiberAwareExecutionContext.liveFibers"),
        // introduced by #3222. Optimized ArrayStack internal API
        ProblemFilters.exclude[Problem]("cats.effect.ArrayStack*"),
        // mystery filters that became required in 3.4.0
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.tracing.TracingConstants.*"),
        // introduced by #3225, which added the BatchingMacrotaskExecutor
        ProblemFilters.exclude[MissingClassProblem](
          "cats.effect.unsafe.FiberAwareExecutionContext"),
        ProblemFilters.exclude[IncompatibleMethTypeProblem](
          "cats.effect.unsafe.ES2021FiberMonitor.this"),
        // introduced by #3324, which specialized CallbackStack for JS
        // internal API change
        ProblemFilters.exclude[IncompatibleTemplateDefProblem]("cats.effect.CallbackStack"),
        // introduced by #3642, which optimized the BatchingMacrotaskExecutor
        ProblemFilters.exclude[MissingClassProblem](
          "cats.effect.unsafe.BatchingMacrotaskExecutor$executeBatchTaskRunnable$")
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
          ProblemFilters.exclude[ReversedMissingMethodProblem](
            "cats.effect.unsafe.WorkStealingThreadPool.canExecuteBlockingCode"),
          ProblemFilters.exclude[ReversedMissingMethodProblem](
            "cats.effect.unsafe.FiberMonitor.monitorSuspended")
        )
      } else Seq()
    }
  )
  .disablePlugins(JCStressPlugin)

/**
 * Test support for the core project, providing various helpful instances like ScalaCheck
 * generators for IO and SyncIO.
 */
lazy val testkit = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("testkit"))
  .dependsOn(core, kernelTestkit)
  .settings(
    name := "cats-effect-testkit",
    libraryDependencies ++= Seq(
      "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion,
      "org.specs2" %%% "specs2-core" % Specs2Version % Test
    )
  )
  .disablePlugins(JCStressPlugin)

/**
 * Unit tests for the core project, utilizing the support provided by testkit.
 */
lazy val tests: CrossProject = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("tests"))
  .dependsOn(core, laws % Test, kernelTestkit % Test, testkit % Test)
  .enablePlugins(BuildInfoPlugin, NoPublishPlugin)
  .settings(
    name := "cats-effect-tests",
    libraryDependencies ++= Seq(
      "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion,
      "org.specs2" %%% "specs2-scalacheck" % Specs2Version % Test,
      "org.typelevel" %%% "discipline-specs2" % DisciplineVersion % Test,
      "org.typelevel" %%% "cats-kernel-laws" % CatsVersion % Test
    ),
    buildInfoPackage := "catseffect",
    githubWorkflowArtifactUpload := false
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
lazy val std = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("std"))
  .dependsOn(kernel)
  .settings(
    name := "cats-effect-std",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "scalac-compat-annotation" % ScalacCompatVersion % CompileTime,
      "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion % Test,
      "org.specs2" %%% "specs2-scalacheck" % Specs2Version % Test
    ),
    mimaBinaryIssueFilters ++= Seq(
      // introduced by #2604, Fix Console on JS
      // changes to `cats.effect.std` package private code
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.std.Console$SyncConsole"),
      // introduced by #2951
      // added configurability to Supervisor's scope termination behavior
      // the following are package-private APIs
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "cats.effect.std.Supervisor#State.add"),
      ProblemFilters.exclude[ReversedMissingMethodProblem](
        "cats.effect.std.Supervisor#State.add"),
      ProblemFilters.exclude[ReversedMissingMethodProblem](
        "cats.effect.std.Supervisor#State.joinAll"),
      // introduced by #3000
      // package-private or private stuff
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.std.Queue#AbstractQueue.onOfferNoCapacity"),
      ProblemFilters.exclude[ReversedMissingMethodProblem](
        "cats.effect.std.Queue#AbstractQueue.onOfferNoCapacity"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.std.Queue#BoundedQueue.onOfferNoCapacity"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.std.Queue#CircularBufferQueue.onOfferNoCapacity"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.std.Queue#DroppingQueue.onOfferNoCapacity"),
      // #3524, private class
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.std.MapRef#ConcurrentHashMapImpl.keys"),
      // introduced by #3346
      // private stuff
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.std.Mutex$Impl"),
      // introduced by #3347
      // private stuff
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.std.AtomicCell$Impl"),
      // introduced by #3409
      // extracted UnsafeUnbounded private data structure
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.std.Queue$UnsafeUnbounded"),
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.std.Queue$UnsafeUnbounded$Cell"),
      // introduced by #3480
      // adds method to sealed Hotswap
      ProblemFilters.exclude[ReversedMissingMethodProblem]("cats.effect.std.Hotswap.get")
    )
  )
  .jsSettings(
    libraryDependencies += "org.scala-js" %%% "scala-js-macrotask-executor" % MacrotaskExecutorVersion % Test,
    mimaBinaryIssueFilters ++= Seq(
      // introduced by #2604, Fix Console on JS
      // changes to a static forwarder, which are meaningless on JS
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("cats.effect.std.Console.make"),
      // introduced by #2905, Add a SecureRandom algebra
      // relocated a package-private class
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.std.JavaSecureRandom"),
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.std.JavaSecureRandom$")
    )
  )
  .disablePlugins(JCStressPlugin)

/**
 * A trivial pair of trivial example apps primarily used to show that IOApp works as a practical
 * runtime on both target platforms.
 */
lazy val example = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("example"))
  .dependsOn(core)
  .enablePlugins(NoPublishPlugin)
  .settings(name := "cats-effect-example")
  .jsSettings(scalaJSUseMainModuleInitializer := true)

/**
 * A trivial app to test GraalVM Native image with.
 */
lazy val graalVMExample = project
  .in(file("graalvm-example"))
  .dependsOn(core.jvm)
  .enablePlugins(NoPublishPlugin, NativeImagePlugin)
  .settings(
    name := "cats-effect-graalvm-example",
    nativeImageOptions ++= Seq("--no-fallback", "-H:+ReportExceptionStackTraces"),
    nativeImageInstalled := true
  )

/**
 * JMH benchmarks for IO and other things.
 */
lazy val benchmarks = project
  .in(file("benchmarks"))
  .dependsOn(core.jvm, std.jvm)
  .settings(
    name := "cats-effect-benchmarks",
    javaOptions ++= Seq(
      "-Dcats.effect.tracing.mode=none",
      "-Dcats.effect.tracing.exceptions.enhanced=false"))
  .enablePlugins(NoPublishPlugin, JmhPlugin)

lazy val stressTests = project
  .in(file("stress-tests"))
  .dependsOn(core.jvm, std.jvm)
  .settings(
    name := "cats-effect-stress-tests",
    Jcstress / version := "0.16"
  )
  .enablePlugins(NoPublishPlugin, JCStressPlugin)

lazy val docs = project.in(file("site-docs")).dependsOn(core.jvm).enablePlugins(MdocPlugin)
