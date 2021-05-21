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
      "org.typelevel" %% "cats-effect" % "2.5.1"
    )
  )

lazy val v2_4_0_output = project
  .in(file("v2_4_0/output"))
  .settings(
    libraryDependencies ++= Seq(
      // Ideally this would be a 2.4.0 release candidate or any other version
      // newer than 2.3.3.
      "org.typelevel" %% "cats-effect" % "3.1.1"
    )
  )

lazy val v2_4_0_tests = project
  .in(file("v2_4_0/tests"))
  .settings(
    libraryDependencies += "ch.epfl.scala" % "scalafix-testkit" % V.scalafixVersion % Test cross CrossVersion.full,
    compile.in(Compile) :=
      compile.in(Compile).dependsOn(compile.in(v2_4_0_input, Compile)).value,
    scalafixTestkitOutputSourceDirectories :=
      sourceDirectories.in(v2_4_0_output, Compile).value,
    scalafixTestkitInputSourceDirectories :=
      sourceDirectories.in(v2_4_0_input, Compile).value,
    scalafixTestkitInputClasspath :=
      fullClasspath.in(v2_4_0_input, Compile).value
  )
  .dependsOn(v2_4_0_input, rules)
  .enablePlugins(ScalafixTestkitPlugin)
