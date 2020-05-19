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

ThisBuild / crossScalaVersions := Seq("2.12.11", "2.13.2")

ThisBuild / githubWorkflowTargetBranches := Seq("ce3")      // for now
ThisBuild / githubWorkflowJavaVersions := Seq("adopt@1.8", "adopt@11", "adopt@14", "graalvm@20.0.0")
ThisBuild / githubWorkflowBuild := WorkflowStep.Sbt(List("ci"))
ThisBuild / githubWorkflowPublishTargetBranches := Seq()    // disable the publication job

Global / homepage := Some(url("https://github.com/typelevel/cats-effect"))

Global / scmInfo := Some(
  ScmInfo(
    url("https://github.com/typelevel/cats-effect"),
    "git@github.com:typelevel/cats-effect.git"))

val CatsVersion = "2.1.1"

lazy val root = project.in(file(".")).aggregate(core, laws)

lazy val core = project.in(file("core"))
  .settings(
    name := "cats-effect",

    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % CatsVersion,
      "org.typelevel" %% "cats-free" % CatsVersion,

      "com.codecommit" %% "coop" % "0.4.0",

      "org.typelevel" %% "cats-laws"         % CatsVersion % Test,
      "org.typelevel" %% "discipline-specs2" % "1.0.0"     % Test,
      "org.specs2"    %% "specs2-scalacheck" % "4.8.1"     % Test))

lazy val laws = project.in(file("laws"))
  .dependsOn(core)
  .settings(
    name := "cats-effect-laws",

    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-laws" % CatsVersion,

      "org.typelevel" %% "discipline-specs2" % "1.0.0"     % Test,
      "org.specs2"    %% "specs2-scalacheck" % "4.8.1"     % Test))
