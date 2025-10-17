/*
 * Copyright 2020-2025 Typelevel
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

import com.typesafe.tools.mima.core._
import com.github.sbt.git.SbtGit.GitKeys._
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.firefox.{FirefoxOptions, FirefoxProfile}
import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.jsenv.selenium.SeleniumJSEnv
import sbtcrossproject.CrossProject
import scala.scalanative.build._

import JSEnv._

lazy val inCI = Option(System.getenv("CI")).contains("true")

// sbt-git workarounds
ThisBuild / useConsoleForROGit := !inCI

ThisBuild / git.gitUncommittedChanges := {
  if ((ThisBuild / githubIsWorkflowBuild).value) {
    git.gitUncommittedChanges.value
  } else {
    import scala.sys.process._
    import scala.util.Try

    Try("git status -s".!!.trim.length > 0).getOrElse(true)
  }
}

ThisBuild / tlBaseVersion := "3.7"
ThisBuild / tlUntaggedAreSnapshots := false

ThisBuild / organization := "org.typelevel"
ThisBuild / organizationName := "Typelevel"

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
val ArmOS = "ubuntu-22.04-arm"
val Windows = "windows-latest"
val MacOS = "macos-14"

val Scala212 = "2.12.20"
val Scala213 = "2.13.16"
val Scala3 = "3.3.7"

ThisBuild / crossScalaVersions := Seq(Scala3, Scala212, Scala213)
ThisBuild / githubWorkflowScalaVersions := crossScalaVersions.value
ThisBuild / tlVersionIntroduced := Map("3" -> "3.1.1")
ThisBuild / tlJdkRelease := Some(8)
ThisBuild / javacOptions += "-Xlint:-options" // --release 8 is deprecated on 21

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
val LoomJava = JavaSpec.temurin("21")
val ScalaJSJava = OldGuardJava
val ScalaNativeJava = OldGuardJava
val GraalVM = JavaSpec.graalvm("21")

ThisBuild / githubWorkflowJavaVersions := Seq(
  OldGuardJava,
  LTSJava,
  LatestJava,
  LoomJava,
  GraalVM)
ThisBuild / githubWorkflowOSes := Seq(PrimaryOS, ArmOS, Windows, MacOS)

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
  WorkflowStep.Use(
    UseRef.Public(
      "al-cheb",
      "configure-pagefile-action",
      "d298bdee6b133626425040e3788f1055a8b4cf7a"),
    name = Some("Configure Windows Pagefile"),
    params = Map("minimum-size" -> "2GB", "maximum-size" -> "8GB", "timeout" -> "600"),
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
      s"(matrix.scala == '$Scala213' || matrix.scala == '$Scala3') && matrix.ci == 'ciJVM' && matrix.java == '${LatestJava.render}'")
  ),
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
    List("example/test-native.sh ${{ matrix.scala }}"),
    name = Some("Test Example Native App Using Binary"),
    cond = Some(s"matrix.ci == 'ciNative' && matrix.os == '$PrimaryOS'")
  ),
  WorkflowStep.Sbt(
    List("graalVMExample/nativeImage", "graalVMExample/nativeImageRun"),
    name = Some("Test GraalVM Native Image"),
    cond = Some(
      s"(matrix.scala == '$Scala213' || matrix.scala == '$Scala3') && matrix.java == '${GraalVM.render}' && matrix.os == '$PrimaryOS'")
  ),
  WorkflowStep.Run(
    List("cd scalafix", "sbt test"),
    name = Some("Scalafix tests"),
    cond =
      Some(s"matrix.scala == '$Scala213' && matrix.ci == 'ciJVM' && matrix.os == '$PrimaryOS'")
  )
)

ThisBuild / githubWorkflowPublish +=
  WorkflowStep.Run(
    List("scripts/post-release-discord.sh ${{ github.ref_name }}"),
    name = Some("Post release to Discord"),
    env = Map("DISCORD_WEBHOOK_URL" -> "${{ secrets.DISCORD_WEBHOOK_URL }}")
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

  val armFilters =
    (ThisBuild / githubWorkflowJavaVersions).value.filterNot(Set(LatestJava)).map { java =>
      MatrixExclude(Map("os" -> ArmOS, "java" -> java.render))
    }

  val windowsAndMacScalaFilters =
    (ThisBuild / githubWorkflowScalaVersions).value.filterNot(Set(Scala213)).flatMap { scala =>
      Seq(
        MatrixExclude(Map("os" -> Windows, "scala" -> scala, "ci" -> CI.JVM.command)),
        MatrixExclude(Map("os" -> MacOS, "scala" -> scala, "ci" -> CI.JVM.command)))
    } ++ Seq(
      MatrixExclude(Map("os" -> MacOS, "java" -> OldGuardJava.render)),
      MatrixExclude(Map("os" -> MacOS, "java" -> LTSJava.render))
    )

  val jsScalaFilters = for {
    scala <- (ThisBuild / githubWorkflowScalaVersions).value.filterNot(Set(Scala213))
    ci <- jsCiVariants.tail
  } yield MatrixExclude(Map("ci" -> ci, "scala" -> scala))

  val jsJavaAndOSFilters = jsCiVariants.flatMap { ci =>
    val javaFilters =
      (ThisBuild / githubWorkflowJavaVersions).value.filterNot(Set(ScalaJSJava)).map { java =>
        MatrixExclude(Map("ci" -> ci, "java" -> java.render))
      }

    val osFilters =
      (ThisBuild / githubWorkflowOSes).value.tail.map { os =>
        MatrixExclude(Map("os" -> os, "ci" -> ci))
      }

    javaFilters ++ osFilters
  }

  val nativeJavaAndOSFilters = {
    val ci = CI.Native.command

    val javaFilters = for {
      java <- (ThisBuild / githubWorkflowJavaVersions).value.filterNot(Set(ScalaNativeJava))
      os <- (ThisBuild / githubWorkflowOSes).value
      if !(Set(ArmOS, MacOS).contains(os) && java == LatestJava)
    } yield MatrixExclude(Map("ci" -> ci, "java" -> java.render, "os" -> os))

    val osFilters = Seq(
      MatrixExclude(Map("os" -> ArmOS, "ci" -> ci, "scala" -> Scala212)),
      MatrixExclude(Map("os" -> Windows, "ci" -> ci)),
      MatrixExclude(Map("os" -> MacOS, "ci" -> ci, "scala" -> Scala212))
    )

    javaFilters ++ osFilters
  }

  scalaJavaFilters ++ armFilters ++ windowsAndMacScalaFilters ++ jsScalaFilters ++ jsJavaAndOSFilters ++ nativeJavaAndOSFilters
}

lazy val useJSEnv =
  settingKey[JSEnv]("Use Node.js or a headless browser for running Scala.js tests")
Global / useJSEnv := NodeJS

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

ThisBuild / Test / testOptions += Tests.Argument("+l")

val CatsVersion = "2.13.0"
val CatsMtlVersion = "1.6.0"
val ScalaCheckVersion = "1.19.0"
val CoopVersion = "1.3.0"
val MUnitVersion = "1.1.0"
val MUnitScalaCheckVersion = "1.1.0"
val DisciplineMUnitVersion = "2.0.0"

val MacrotaskExecutorVersion = "1.1.1"

Global / tlCommandAliases ++= Map(
  CI.JVM.commandAlias,
  CI.Native.commandAlias,
  CI.JS.commandAlias,
  CI.Firefox.commandAlias,
  CI.Chrome.commandAlias
)

Global / tlCommandAliases ++= Map(
  "ci" -> CI.AllCIs.flatMap(_.commands)
)

Global / tlCommandAliases ++= Map(
  "release" -> List("tlRelease")
)

Global / tlCommandAliases ++= Map(
  "prePR" -> List(
    "root/clean",
    "+root/headerCreate",
    "root/scalafixAll",
    "scalafmtSbt",
    "+root/scalafmtAll"
  )
)

lazy val nativeTestSettings = Seq(
  nativeConfig ~= { c =>
    c.withSourceLevelDebuggingConfig(_.enableAll.generateFunctionSourcePositions(false))
      .withOptimize(
        true
      ) // `false` doesn't work due to https://github.com/scala-native/scala-native/issues/4366
      .withMode(Mode.debug) // compile using LLVM without optimizations
  },
  envVars ++= { if (inCI) Map("GC_MAXIMUM_HEAP_SIZE" -> "10g") else Map.empty[String, String] },
  parallelExecution := !inCI
)

val jsProjects: Seq[ProjectReference] =
  Seq(
    kernel.js,
    kernelTestkit.js,
    laws.js,
    core.js,
    testkit.js,
    tests.js,
    ioAppTestsJS,
    std.js,
    example.js)

val nativeProjects: Seq[ProjectReference] =
  Seq(
    kernel.native,
    kernelTestkit.native,
    laws.native,
    core.native,
    testkit.native,
    tests.native,
    ioAppTestsNative,
    std.native,
    example.native)

val undocumentedRefs =
  jsProjects ++ nativeProjects ++ Seq[ProjectReference](
    benchmarks,
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
    },
    scalacOptions -= "-Xsource:3" // bugged
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
    graalVMExample,
    benchmarks)
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
      "org.typelevel" %%% "cats-core" % CatsVersion
    ),
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.kernel.Ref$SyncRef"),
      ProblemFilters.exclude[Problem]("cats.effect.kernel.GenConcurrent#Memoize*")
    )
  )
  .jsSettings(
    libraryDependencies += "org.scala-js" %%% "scala-js-macrotask-executor" % MacrotaskExecutorVersion % Test
  )
  .nativeSettings(
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % "2.6.0"
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
    scalacOptions -= "-Xsource:3", // bugged
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
      "org.typelevel" %%% "discipline-munit" % DisciplineMUnitVersion % Test)
  )

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
      "org.typelevel" %%% "cats-mtl" % CatsMtlVersion
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
        "cats.effect.IOFiberConstants.ContStateResult"),
      // #3775, changes to internal timers APIs
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "cats.effect.unsafe.TimerSkipList.insert"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "cats.effect.unsafe.WorkerThread.sleep"),
      // #3787, internal utility that was no longer needed
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.Thunk"),
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.Thunk$"),
      // #3781, replaced TimerSkipList with TimerHeap
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.TimerSkipList*"),
      // #3943, refactored internal private CallbackStack data structure
      ProblemFilters.exclude[IncompatibleResultTypeProblem]("cats.effect.CallbackStack.push"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.CallbackStack.currentHandle"),
      // #3973, remove clear from internal private CallbackStack
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.CallbackStack.clear"),
      // introduced by #3332, polling system
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.IORuntimeBuilder.this"),
      // introduced by #3695, which enabled fiber dumps on native
      ProblemFilters.exclude[MissingClassProblem](
        "cats.effect.unsafe.FiberMonitorCompanionPlatform"),
      // introduced by #3636, IOLocal propagation
      // IOLocal is a sealed trait
      ProblemFilters.exclude[ReversedMissingMethodProblem]("cats.effect.IOLocal.getOrDefault"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("cats.effect.IOLocal.set"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("cats.effect.IOLocal.reset"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("cats.effect.IOLocal.lens"),
      // this filter is particulary terrible, because it can also mask real issues :(
      ProblemFilters.exclude[DirectMissingMethodProblem]("cats.effect.IOLocal.lens"),
      // internal API change, makes CpuStarvationMetrics available on all platforms
      ProblemFilters.exclude[MissingClassProblem](
        "cats.effect.metrics.JvmCpuStarvationMetrics$NoOpCpuStarvationMetrics"),
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.metrics.CpuStarvationMetrics"),
      // package-private classes moved to the `cats.effect.unsafe.metrics` package
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.metrics.CpuStarvation"),
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.metrics.CpuStarvation$"),
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.metrics.CpuStarvationMBean"),
      // changes to the `cats.effect.unsafe` package private code, see #4406
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.WorkerThread.getSuspendedFiberCount"),
      // protected constructor modified when fixing #4359
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "cats.effect.unsafe.IORuntimeBuilder.<init>$default$10")
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
          ProblemFilters.exclude[AbstractClassProblem]("cats.effect.ExitCode"),
          // #3934 which made these internal vals into proper static fields
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.IORuntime.allRuntimes"),
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.unsafe.IORuntime.globalFatalFailureHandled")
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
          "cats.effect.unsafe.BatchingMacrotaskExecutor$executeBatchTaskRunnable$"),
        // #3943, refactored internal private CallbackStack data structure
        ProblemFilters.exclude[Problem]("cats.effect.CallbackStackOps.*"),
        // introduced by #3695, which ported fiber monitoring to Native
        // internal API change
        ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.ES2021FiberMonitor"),
        // internal API change, makes CpuStarvationMetrics available on all platforms
        ProblemFilters.exclude[MissingClassProblem](
          "cats.effect.metrics.JsCpuStarvationMetrics"),
        ProblemFilters.exclude[MissingClassProblem](
          "cats.effect.metrics.JsCpuStarvationMetrics$"),
        // all package-private classes; introduced when we made Native multithreaded
        ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.FiberExecutor"),
        ProblemFilters.exclude[IncompatibleMethTypeProblem](
          "cats.effect.unsafe.FiberMonitorImpl.this"),
        ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.FiberMonitorPlatform"),
        // all of the following are introduced by fixing #4359
        // the first one is legitimate and was a public signature in 3.6.x (but a silently non-functional one)
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.unsafe.IORuntimeBuilder.addPoller"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.unsafe.IORuntimeBuilder.extraPollers"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.unsafe.IORuntimeBuilder.extraPollers_="),
        // internal API change
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.unsafe.NoOpFiberMonitor.liveFiberSnapshot"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.unsafe.FiberMonitorImpl.liveFiberSnapshot")
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
  .nativeSettings(
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters.exclude[MissingClassProblem](
        "cats.effect.unsafe.PollingExecutorScheduler$SleepTask"),
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.QueueExecutorScheduler"),
      ProblemFilters.exclude[MissingClassProblem]("cats.effect.unsafe.QueueExecutorScheduler$"),
      // internal API change, makes CpuStarvationMetrics available on all platforms
      ProblemFilters.exclude[MissingClassProblem](
        "cats.effect.metrics.NativeCpuStarvationMetrics"),
      ProblemFilters.exclude[MissingClassProblem](
        "cats.effect.metrics.NativeCpuStarvationMetrics$")
    )
  )

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
      "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion
    )
  )
  .nativeSettings(nativeTestSettings)

/**
 * Unit tests for the core project, utilizing the support provided by testkit.
 */
lazy val tests: CrossProject = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("tests"))
  .dependsOn(core, laws % Test, kernelTestkit % Test, testkit % Test)
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "cats-effect-tests",
    libraryDependencies ++= Seq(
      "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion,
      "org.scalameta" %%% "munit" % MUnitVersion % Test,
      "org.scalameta" %%% "munit-scalacheck" % MUnitScalaCheckVersion % Test,
      "org.typelevel" %%% "discipline-munit" % DisciplineMUnitVersion % Test,
      "org.typelevel" %%% "cats-kernel-laws" % CatsVersion % Test,
      "org.typelevel" %%% "cats-mtl-laws" % CatsMtlVersion % Test
    ),
    githubWorkflowArtifactUpload := false
  )
  .jsSettings(
    Compile / scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("catseffect.examples.JSRunner"),
    // The default configured mapSourceURI is used for trace filtering
    scalacOptions ~= { _.filterNot(_.startsWith("-P:scalajs:mapSourceURI")) }
  )
  .jvmSettings(
    fork := true,
    Test / javaOptions += "-Dcats.effect.trackFiberContext=true"
  )
  .nativeSettings(
    Compile / mainClass := Some("catseffect.examples.NativeRunner"),
    nativeTestSettings
  )

def configureIOAppTests(p: Project): Project =
  p.enablePlugins(NoPublishPlugin, BuildInfoPlugin)
    .settings(
      Test / unmanagedSourceDirectories += (LocalRootProject / baseDirectory).value / "ioapp-tests" / "src" / "test" / "scala",
      libraryDependencies += "org.scalameta" %%% "munit" % MUnitVersion % Test,
      buildInfoPackage := "cats.effect",
      buildInfoKeys ++= Seq(
        "jsRunner" -> (tests.js / Compile / fastOptJS / artifactPath).value,
        "nativeRunner" -> (tests.native / Compile / crossTarget).value / (tests.native / Compile / moduleName).value
      )
    )

lazy val ioAppTestsJVM =
  project
    .in(file("ioapp-tests/.jvm"))
    .configure(configureIOAppTests)
    .settings(
      buildInfoKeys += "platform" -> "jvm",
      Test / fork := true,
      Test / javaOptions += s"-Dcatseffect.examples.classpath=${(tests.jvm / Compile / fullClasspath).value.map(_.data.getAbsolutePath).mkString(File.pathSeparator)}"
    )

lazy val ioAppTestsJS =
  project
    .in(file("ioapp-tests/.js"))
    .configure(configureIOAppTests)
    .settings(
      (Test / test) := (Test / test).dependsOn(tests.js / Compile / fastOptJS).value,
      buildInfoKeys += "platform" -> "js"
    )

lazy val ioAppTestsNative =
  project
    .in(file("ioapp-tests/.native"))
    .configure(configureIOAppTests)
    .settings(
      (Test / test) := (Test / test).dependsOn(tests.native / Compile / nativeLink).value,
      buildInfoKeys += "platform" -> "native"
    )

/**
 * Implementations of standard functionality (e.g. Semaphore, Console, Queue) purely in terms of
 * the typeclasses, with no dependency on IO. In most cases, the *tests* for these
 * implementations will require IO, and thus those tests will be located within the core
 * project.
 */
lazy val std = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("std"))
  .dependsOn(kernel)
  .settings(
    name := "cats-effect-std",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % MUnitVersion % Test
    ),
    mimaBinaryIssueFilters ++= {
      if (tlIsScala3.value) {
        Seq(
          ProblemFilters.exclude[DirectMissingMethodProblem](
            "cats.effect.std.Supervisor.apply$default$2")
        )
      } else Seq()
    },
    mimaBinaryIssueFilters ++=
      Seq(
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
        ProblemFilters.exclude[MissingClassProblem](
          "cats.effect.std.Queue$UnsafeUnbounded$Cell"),
        // introduced by #3480
        // adds method to sealed Hotswap
        ProblemFilters.exclude[ReversedMissingMethodProblem]("cats.effect.std.Hotswap.get"),
        // #3972, private trait
        ProblemFilters.exclude[IncompatibleTemplateDefProblem](
          "cats.effect.std.Supervisor$State"),
        // introduced by #3923
        // Rewrote Dispatcher
        ProblemFilters.exclude[MissingClassProblem]("cats.effect.std.Dispatcher$Mode"),
        ProblemFilters.exclude[MissingClassProblem]("cats.effect.std.Dispatcher$Mode$"),
        ProblemFilters.exclude[MissingClassProblem](
          "cats.effect.std.Dispatcher$Mode$Parallel$"),
        ProblemFilters.exclude[MissingClassProblem](
          "cats.effect.std.Dispatcher$Mode$Sequential$"),
        // #4052, private classes
        ProblemFilters.exclude[MissingTypesProblem](
          "cats.effect.std.Dispatcher$RegState$Unstarted$"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.std.Dispatcher#RegState#Unstarted.*"),
        ProblemFilters.exclude[FinalMethodProblem](
          "cats.effect.std.Dispatcher#RegState#Unstarted.toString"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.std.Dispatcher#Registration#Primary.*"),
        // #4065, moved to its own file.
        ProblemFilters.exclude[MissingClassProblem]("cats.effect.std.Mutex$ConcurrentImpl$"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.std.Mutex#ConcurrentImpl.EmptyCell"),
        ProblemFilters.exclude[DirectMissingMethodProblem](
          "cats.effect.std.Mutex#ConcurrentImpl.LockQueueCell"),
        // #4424, refactored private classes
        ProblemFilters.exclude[IncompatibleMethTypeProblem](
          "cats.effect.std.AtomicCell#AsyncImpl.this"),
        ProblemFilters.exclude[IncompatibleMethTypeProblem](
          "cats.effect.std.AtomicCell#ConcurrentImpl.this"),
        // #4424, false warnings in CommonImpl due to lightbend-labs/mima#211
        ProblemFilters.exclude[DirectAbstractMethodProblem](
          "cats.effect.std.AtomicCell.modify"),
        ProblemFilters.exclude[DirectAbstractMethodProblem](
          "cats.effect.std.AtomicCell.evalUpdate"),
        ProblemFilters.exclude[DirectAbstractMethodProblem](
          "cats.effect.std.AtomicCell.evalGetAndUpdate"),
        ProblemFilters.exclude[DirectAbstractMethodProblem](
          "cats.effect.std.AtomicCell.evalUpdateAndGet")
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
    fork := true,
    javaOptions ++= Seq(
      "-Dcats.effect.tracing.mode=none",
      "-Dcats.effect.tracing.exceptions.enhanced=false"))
  .enablePlugins(NoPublishPlugin, JmhPlugin)

lazy val docs = project
  .in(file("site-docs"))
  .dependsOn(core.jvm)
  .enablePlugins(MdocPlugin)
  .settings(tlFatalWarnings := { if (tlIsScala3.value) false else tlFatalWarnings.value })
