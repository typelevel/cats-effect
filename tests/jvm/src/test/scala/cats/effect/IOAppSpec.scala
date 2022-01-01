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

package cats.effect

import org.specs2.mutable.Specification

import java.io.File
import scala.io.Source
import scala.sys.process.BasicIO
import scala.sys.process.Process
import scala.sys.process.ProcessBuilder

class IOAppSpec extends Specification {

  abstract class Platform(val id: String) { outer =>
    def builder(proto: IOApp, args: List[String]): ProcessBuilder
    def pid(proto: IOApp): Option[Int]

    def dumpSignal: String

    def sendSignal(pid: Int): Unit = {
      Runtime.getRuntime().exec(s"kill -$dumpSignal $pid")
      ()
    }

    def apply(proto: IOApp, args: List[String]): Handle = {
      val stdoutBuffer = new StringBuffer()
      val stderrBuffer = new StringBuffer()
      val p = builder(proto, args).run(BasicIO(false, stdoutBuffer, None).withError { in =>
        val err = Source.fromInputStream(in).getLines().mkString(System.lineSeparator())
        stderrBuffer.append(err)
        ()
      })

      new Handle {
        def awaitStatus() = p.exitValue()
        def term() = p.destroy() // TODO probably doesn't work
        def stderr() = stderrBuffer.toString
        def stdout() = stdoutBuffer.toString
        def pid() = outer.pid(proto)
      }
    }
  }

  object JVM extends Platform("jvm") {
    val ClassPath = System.getProperty("sbt.classpath")

    val JavaHome = {
      val path = sys.env.get("JAVA_HOME").orElse(sys.props.get("java.home")).get
      if (path.endsWith("/jre")) {
        // handle JDK 8 installations
        path.replace("/jre", "")
      } else {
        path
      }
    }

    val dumpSignal = "USR1"

    def builder(proto: IOApp, args: List[String]) = Process(
      s"$JavaHome/bin/java",
      List("-cp", ClassPath, proto.getClass.getName.replaceAll("\\$$", "")) ::: args)

    // scala.sys.process.Process and java.lang.Process lack getting PID support. Java 9+ introduced it but
    // whatever because it's very hard to obtain a java.lang.Process from scala.sys.process.Process.
    def pid(proto: IOApp): Option[Int] = {
      val mainName = proto.getClass.getSimpleName.replace("$", "")
      val jpsStdoutBuffer = new StringBuffer()
      val jpsProcess =
        Process(s"$JavaHome/bin/jps", List.empty).run(BasicIO(false, jpsStdoutBuffer, None))
      jpsProcess.exitValue()

      val output = jpsStdoutBuffer.toString
      Source.fromString(output).getLines().find(_.contains(mainName)).map(_.split(" ")(0).toInt)
    }

  }

  object Node extends Platform("node") {
    val dumpSignal = "USR2"

    def builder(proto: IOApp, args: List[String]) =
      Process(
        s"node",
        "--enable-source-maps" :: BuildInfo
          .jsRunner
          .getAbsolutePath :: proto.getClass.getName.init :: args)

    def pid(proto: IOApp): Option[Int] = {
      val mainName = proto.getClass.getName.init
      val stdoutBuffer = new StringBuffer()
      val process =
        Process("ps", List("aux")).run(BasicIO(false, stdoutBuffer, None))
      process.exitValue()

      val output = stdoutBuffer.toString
      Source
        .fromString(output)
        .getLines()
        .find(l => l.contains(BuildInfo.jsRunner.getAbsolutePath) && l.contains(mainName))
        .map(_.split(" +")(1).toInt)
    }
  }

  if (BuildInfo.testJSIOApp)
    test(Node)
  else
    test(JVM)

  def test(platform: Platform): Unit = {
    s"IOApp (${platform.id})" should {
      import catseffect.examples._

      "evaluate and print hello world" in {
        val h = platform(HelloWorld, Nil)
        h.awaitStatus() mustEqual 0
        h.stdout() mustEqual s"Hello, World!${System.lineSeparator()}"
      }

      "pass all arguments to child" in {
        val expected = List("the", "quick", "brown", "fox jumped", "over")
        val h = platform(Arguments, expected)
        h.awaitStatus() mustEqual 0
        h.stdout() mustEqual expected.mkString(
          "",
          System.lineSeparator(),
          System.lineSeparator())
      }

      if (System.getProperty("os.name").toLowerCase.contains("windows")) {
        // The jvm cannot gracefully terminate processes on Windows, so this
        // test cannot be carried out properly. Same for testing IOApp in sbt.
        "run finalizers on TERM" in skipped(
          "cannot observe graceful process termination on Windows")
        "exit on fatal error" in skipped(
          "cannot observe graceful process termination on Windows")
        "exit on fatal error with other unsafe runs" in skipped(
          "cannot observe graceful process termination on Windows")
        "exit on canceled" in skipped("cannot observe graceful process termination on Windows")
        "warn on global runtime collision" in skipped(
          "cannot observe graceful process termination on Windows")
        "abort awaiting shutdown hooks" in skipped(
          "cannot observe graceful process termination on Windows")
        "live fiber snapshot" in skipped(
          "cannot observe graceful process termination on Windows")
      } else {
        "run finalizers on TERM" in {
          if (System.getProperty("os.name").toLowerCase.contains("windows")) {
            // The jvm cannot gracefully terminate processes on Windows, so this
            // test cannot be carried out properly. Same for testing IOApp in sbt.
            ok
          } else {
            import _root_.java.io.{BufferedReader, FileReader}

            // we have to resort to this convoluted approach because Process#destroy kills listeners before killing the process
            val test = File.createTempFile("cats-effect", "finalizer-test")
            def readTest(): String = {
              val reader = new BufferedReader(new FileReader(test))
              try {
                reader.readLine()
              } finally {
                reader.close()
              }
            }

            val h = platform(Finalizers, test.getAbsolutePath() :: Nil)

            var i = 0
            while (!h.stdout().contains("Started") && i < 100) {
              Thread.sleep(100)
              i += 1
            }

            Thread.sleep(
              100
            ) // give thread scheduling just a sec to catch up and get us into the latch.await()

            h.term()
            h.awaitStatus() mustEqual 143

            i = 0
            while (readTest() == null && i < 100) {
              i += 1
            }
            readTest() must contain("canceled")
          }
        }

        "exit on non-fatal error" in {
          val h = platform(NonFatalError, List.empty)
          h.awaitStatus() mustEqual 1
          h.stderr() must contain("Boom!")
        }

        "exit on fatal error" in {
          val h = platform(FatalError, List.empty)
          h.awaitStatus() mustEqual 1
          h.stderr() must contain("Boom!")
        }

        "exit on fatal error with other unsafe runs" in {
          val h = platform(FatalErrorUnsafeRun, List.empty)
          h.awaitStatus() mustEqual 1
          h.stderr() must contain("Boom!")
        }

        "exit on canceled" in {
          val h = platform(Canceled, List.empty)
          h.awaitStatus() mustEqual 1
        }

        "warn on global runtime collision" in {
          val h = platform(GlobalRacingInit, List.empty)
          h.awaitStatus() mustEqual 0
          h.stderr() must contain(
            "Cats Effect global runtime already initialized; custom configurations will be ignored")
          h.stderr() must not(contain("boom"))
        }

        "abort awaiting shutdown hooks" in {
          val h = platform(ShutdownHookImmediateTimeout, List.empty)
          h.awaitStatus() mustEqual 0
        }

        if (!BuildInfo.testJSIOApp) {
          "shutdown on worker thread interruption" in {
            val h = platform(WorkerThreadInterrupt, List.empty)
            h.awaitStatus() mustEqual 1
            h.stderr() must contain("java.lang.InterruptedException")
            ok
          }
        }

        if (!BuildInfo.testJSIOApp && sys
            .props
            .get("java.version")
            .filter(_.startsWith("1.8"))
            .isDefined) {
          "live fiber snapshot" in skipped(
            "JDK 8 does not have free signals for live fiber snapshots")
        } else {
          "live fiber snapshot" in {
            val h = platform(LiveFiberSnapshot, List.empty)
            // Allow the process some time to start
            // and register the signal handlers.
            Thread.sleep(2000L)
            val pid = h.pid()
            pid must beSome
            pid.foreach(platform.sendSignal)
            h.awaitStatus()
            val stderr = h.stderr()
            stderr must contain("cats.effect.IOFiber")
          }
        }
      }
    }
    ()
  }

  trait Handle {
    def awaitStatus(): Int
    def term(): Unit
    def stderr(): String
    def stdout(): String
    def pid(): Option[Int]
  }
}
