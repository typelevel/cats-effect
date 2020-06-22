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

ThisBuild / baseVersion := "3.0"

ThisBuild / organization := "org.typelevel"
ThisBuild / organizationName := "Typelevel"

ThisBuild / publishGithubUser := "djspiewak"
ThisBuild / publishFullName := "Daniel Spiewak"

ThisBuild / crossScalaVersions := Seq("0.25.0-RC2", "2.12.11", "2.13.2")

ThisBuild / githubWorkflowTargetBranches := Seq("ce3")      // for now
ThisBuild / githubWorkflowJavaVersions := Seq("adopt@1.8", "adopt@11", "adopt@14", "graalvm@20.1.0")

Global / homepage := Some(url("https://github.com/typelevel/cats-effect"))

Global / scmInfo := Some(
  ScmInfo(
    url("https://github.com/typelevel/cats-effect"),
    "git@github.com:typelevel/cats-effect.git"))

val CatsVersion = "2.1.1"

lazy val root = project.in(file("."))
  .aggregate(core.jvm, core.js, laws.jvm, laws.js)
  .settings(noPublishSettings)

lazy val core = crossProject(JSPlatform, JVMPlatform).in(file("core"))
  .settings(
    name := "cats-effect",

    libraryDependencies += "org.typelevel" %%% "cats-core" % CatsVersion)
  .settings(dottyLibrarySettings)
  .settings(dottyJsSettings(ThisBuild / crossScalaVersions))

lazy val laws = crossProject(JSPlatform, JVMPlatform).in(file("laws"))
  .dependsOn(core)
  .settings(
    name := "cats-effect-laws",

    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-laws" % CatsVersion,
      "org.typelevel" %%% "cats-free" % CatsVersion,

      "org.typelevel" %%% "discipline-specs2" % "1.1.0" % Test,
      "org.specs2"    %%% "specs2-scalacheck" % "4.9.4" % Test))
  .settings(dottyLibrarySettings)
  .settings(dottyJsSettings(ThisBuild / crossScalaVersions))
  .settings(libraryDependencies += "com.codecommit" %%% "coop" % "0.6.1")
