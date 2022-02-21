import sbt._, Keys._
import sbtspiewak.SpiewakPlugin, SpiewakPlugin.autoImport._

object Java8Target extends AutoPlugin {

  override def requires = SpiewakPlugin
  override def trigger = allRequirements

  override def projectSettings = Seq(
    scalacOptions ++= {
      val version = System.getProperty("java.version")

      // The release flag controls what JVM platform APIs are called. We only want JDK 8 APIs
      // in order to maintain compability with JDK 8.
      val releaseFlag =
        if (version.startsWith("1.8"))
          Seq()
        else
          Seq("-release", "8")

      // The target flag is not implied by `-release` on Scala 2. We need to set it explicitly.
      // The target flag controls the JVM bytecode version that is output by scalac.
      val targetFlag =
        if (isDotty.value || version.startsWith("1.8"))
          Seq()
        else if (scalaVersion.value.startsWith("2.12"))
          Seq("-target:jvm-1.8")
        else
          Seq("-target:8")

      releaseFlag ++ targetFlag
    },

    javacOptions ++= {
      val version = System.getProperty("java.version")
      if (version.startsWith("1.8"))
        Seq()
      else
        Seq("--release", "8")
    })
}
