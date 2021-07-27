val V = _root_.scalafix.sbt.BuildInfo

inThisBuild(
  List(
    scalaVersion := V.scala213,
    addCompilerPlugin(scalafixSemanticdb)
  )
)

lazy val rules = project.settings(
  libraryDependencies += "ch.epfl.scala" %% "scalafix-core" % V.scalafixVersion
)

lazy val v2_4_0_input = project
  .in(file("v2_4_0/input"))
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "2.3.3"
    )
  )

lazy val v2_4_0_output = project
  .in(file("v2_4_0/output"))
  .settings(
    libraryDependencies ++= Seq(
      // Ideally this would be a 2.4.0 release candidate or any other version
      // newer than 2.3.3.
      "org.typelevel" %% "cats-effect" % "2.4.0"
    )
  )

lazy val v2_4_0_tests = project
  .in(file("v2_4_0/tests"))
  .settings(
    libraryDependencies += ("ch.epfl.scala" % "scalafix-testkit" % V.scalafixVersion % Test).cross(CrossVersion.full),
    (Compile / compile) :=
      (Compile / compile).dependsOn(v2_4_0_input / Compile / compile).value,
    scalafixTestkitOutputSourceDirectories :=
      (v2_4_0_output / Compile / sourceDirectories).value,
    scalafixTestkitInputSourceDirectories :=
      (v2_4_0_input / Compile / sourceDirectories).value,
    scalafixTestkitInputClasspath :=
      (v2_4_0_input / Compile / fullClasspath).value
  )
  .dependsOn(v2_4_0_input, rules)
  .enablePlugins(ScalafixTestkitPlugin)

lazy val v2_5_2_input = project
  .in(file("v2_5_2/input"))
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "2.5.1"
    )
  )

lazy val v2_5_2_output = project
  .in(file("v2_5_2/output"))
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "2.5.2"
    )
  )

lazy val v2_5_2_tests = project
  .in(file("v2_5_2/tests"))
  .settings(
    libraryDependencies += ("ch.epfl.scala" % "scalafix-testkit" % V.scalafixVersion % Test).cross(CrossVersion.full),
    (Compile / compile) :=
      (Compile / compile).dependsOn(v2_5_2_input / Compile / compile).value,
    scalafixTestkitOutputSourceDirectories :=
      (v2_5_2_output / Compile / sourceDirectories).value,
    scalafixTestkitInputSourceDirectories :=
      (v2_5_2_input / Compile / sourceDirectories).value,
    scalafixTestkitInputClasspath :=
      (v2_5_2_input / Compile / fullClasspath).value
  )
  .dependsOn(v2_5_2_input, rules)
  .enablePlugins(ScalafixTestkitPlugin)
