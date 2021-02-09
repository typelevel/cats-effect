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

lazy val v3_0_0_input = project.in(file("v3_0_0/input"))
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "2.3.1"
    )
  )

lazy val v3_0_0_output = project.in(file("v3_0_0/output"))
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.0.0-M5"
    )
  )

lazy val v3_0_0_tests = project.in(file("v3_0_0/tests"))
  .settings(
    libraryDependencies += "ch.epfl.scala" % "scalafix-testkit" % V.scalafixVersion % Test cross CrossVersion.full,
    compile.in(Compile) :=
      compile.in(Compile).dependsOn(compile.in(v3_0_0_input, Compile)).value,
    scalafixTestkitOutputSourceDirectories :=
      sourceDirectories.in(v3_0_0_output, Compile).value,
    scalafixTestkitInputSourceDirectories :=
      sourceDirectories.in(v3_0_0_input, Compile).value,
    scalafixTestkitInputClasspath :=
      fullClasspath.in(v3_0_0_input, Compile).value
  )
  .dependsOn(v3_0_0_input, rules)
  .enablePlugins(ScalafixTestkitPlugin)
