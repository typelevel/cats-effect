/*
 * Copyright 2017 Typelevel
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

import de.heikoseeberger.sbtheader.license.Apache2_0

organization in ThisBuild := "org.typelevel"

val CatsVersion = "0.9.0"
val ScalaCheckVersion = "1.13.4"
val DisciplineVersion = "0.7.3"

addCommandAlias("ci", ";test ;mimaReportBinaryIssues")
addCommandAlias("release", ";reload ;+publishSigned ;sonatypeReleaseAll")

val commonSettings = Seq(
  scalacOptions in (Compile, console) ~= (_ filterNot Set("-Xfatal-warnings", "-Ywarn-unused-import").contains),

  headers := Map(
    "scala" -> Apache2_0("2017", "Typelevel"),
    "java" -> Apache2_0("2017", "Typelevel")),

  isSnapshot := version.value endsWith "SNAPSHOT",      // so… sonatype doesn't like git hash snapshots

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
        <url>http://github.com/mpilquist</url>
      </developer>
    </developers>,

  homepage := Some(url("https://github.com/typelevel/cats-effect")),

  scmInfo := Some(ScmInfo(url("https://github.com/typelevel/cats-effect"), "git@github.com:typelevel/cats-effect.git")),

  addCompilerPlugin("org.spire-math" % "kind-projector" % "0.9.3" cross CrossVersion.binary)
)

val mimaSettings = Seq(
  mimaPreviousArtifacts := {
    val TagBase = """^(\d+)\.(\d+).*""".r
    val TagBase(major, minor) = BaseVersion

    val tags = "git tag --list".!! split "\n" map { _.trim }

    val versions =
      tags filter { _ startsWith s"v$major.$minor" } map { _ substring 1 }

    versions.map { v => organization.value %% name.value % v }.toSet
  }
)

lazy val root = project.in(file("."))
  .aggregate(coreJVM, coreJS, lawsJVM, lawsJS)
  .settings(
    publish := (),
    publishLocal := (),
    publishArtifact := false
  )

lazy val core = crossProject
  .in(file("core"))
  .settings(commonSettings: _*)
  .settings(
    name := "cats-effect",

    libraryDependencies ++= Seq(
      "org.typelevel"        %%% "cats-core"  % CatsVersion,
      "com.github.mpilquist" %%% "simulacrum" % "0.10.0",

      "org.typelevel"  %%% "cats-laws"  % CatsVersion       % "test",
      "org.scalatest"  %%% "scalatest"  % "3.0.1"           % "test",
      "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion % "test",
      "org.typelevel"  %%% "discipline" % DisciplineVersion % "test"
    ),

    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
  )
  .jvmConfigure(_.enablePlugins(AutomateHeaderPlugin))
  .jvmConfigure(_.settings(mimaSettings))
  .jsConfigure(_.enablePlugins(AutomateHeaderPlugin))

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

      "org.scalatest"  %%% "scalatest"  % "3.0.1" % "test"))
  .jvmConfigure(_.enablePlugins(AutomateHeaderPlugin))
  .jsConfigure(_.enablePlugins(AutomateHeaderPlugin))

lazy val lawsJVM = laws.jvm
lazy val lawsJS = laws.js

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
val BaseVersion = "0.1"

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
  "-Xlint",
  "-Yno-adapted-args",
  "-Ywarn-dead-code"
)

scalacOptions in ThisBuild ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, major)) if major >= 11 => Seq(
      "-Ywarn-unused-import", // Not available in 2.10
      "-Ywarn-numeric-widen" // In 2.10 this produces a some strange spurious error
    )
    case _ => Seq.empty
  }
}

scalacOptions in ThisBuild ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, major)) if major >= 12 || scalaVersion.value == "2.11.11" =>
      Seq("-Ypartial-unification")

    case _ => Seq.empty
  }
}

scalacOptions in Test += "-Yrangepos"

scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value

libraryDependencies in ThisBuild ++= {
  scalaVersion.value match {
    case "2.11.8" | "2.10.6" => Seq(compilerPlugin("com.milessabin" % "si2712fix-plugin" % "1.2.0" cross CrossVersion.full))
    case _ => Seq.empty
  }
}

enablePlugins(GitVersioning)

val ReleaseTag = """^v([\d\.]+)$""".r

git.baseVersion := BaseVersion

git.gitTagToVersionNumber := {
  case ReleaseTag(version) => Some(version)
  case _ => None
}

git.formattedShaVersion := {
  val suffix = git.makeUncommittedSignifierSuffix(git.gitUncommittedChanges.value, git.uncommittedSignifier.value)

  git.gitHeadCommit.value map { _.substring(0, 7) } map { sha =>
    git.baseVersion.value + "-" + sha + suffix
  }
}
