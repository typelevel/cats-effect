/*
 * Copyright 2020-2023 Typelevel
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

import scala.io.Source
import scala.sys.process.{BasicIO, Process, ProcessBuilder}

import java.io.File

class IOAppSpec extends Specification {

  abstract class Platform(val id: String) { outer =>
    def builder(proto: AnyRef, args: List[String]): ProcessBuilder
    def pid(proto: AnyRef): Option[Int]

    def dumpSignal: String

    def sendSignal(pid: Int): Unit = {
      Runtime.getRuntime().exec(s"kill -$dumpSignal $pid")
      ()
    }

    def apply(proto: AnyRef, args: List[String]): Handle = {
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

    def builder(proto: AnyRef, args: List[String]) = Process(
      s"$JavaHome/bin/java",
      List("-cp", ClassPath, proto.getClass.getName.replaceAll("\\$$", "")) ::: args)

    // scala.sys.process.Process and java.lang.Process lack getting PID support. Java 9+ introduced it but
    // whatever because it's very hard to obtain a java.lang.Process from scala.sys.process.Process.
    def pid(proto: AnyRef): Option[Int] = {
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

    def builder(proto: AnyRef, args: List[String]) =
      Process(
        s"node",
        "--enable-source-maps" :: BuildInfo
          .jsRunner
          .getAbsolutePath :: proto.getClass.getName.init :: args)

    def pid(proto: AnyRef): Option[Int] = {
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

      val isWindows = System.getProperty("os.name").toLowerCase.contains("windows")

      if (isWindows) {
        // these tests have all been emperically flaky on Windows CI builds, so they're disabled
        "evaluate and print hello world" in skipped("this test is unreliable on Windows")
        "pass all arguments to child" in skipped("this test is unreliable on Windows")

        "exit with leaked fibers" in skipped("this test is unreliable on Windows")

        "exit on non-fatal error" in skipped("this test is unreliable on Windows")
        "exit on fatal error" in skipped("this test is unreliable on Windows")

        "exit on fatal error with other unsafe runs" in skipped(
          "this test is unreliable on Windows")

        "warn on global runtime collision" in skipped("this test is unreliable on Windows")
        "abort awaiting shutdown hooks" in skipped("this test is unreliable on Windows")

        // The jvm cannot gracefully terminate processes on Windows, so this
        // test cannot be carried out properly. Same for testing IOApp in sbt.
        "run finalizers on TERM" in skipped(
          "cannot observe graceful process termination on Windows")
      } else {
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

        "exit on non-fatal error" in {
          val h = platform(NonFatalError, List.empty)
          h.awaitStatus() mustEqual 1
          h.stderr() must contain("Boom!")
        }

        "exit with leaked fibers" in {
          val h = platform(LeakedFiber, List.empty)
          h.awaitStatus() mustEqual 0
        }

        "exit on fatal error" in {
          val h = platform(FatalError, List.empty)
          h.awaitStatus() mustEqual 1
          h.stderr() must contain("Boom!")
          h.stdout() must not(contain("sadness"))
        }

        "exit on fatal error with other unsafe runs" in {
          val h = platform(FatalErrorUnsafeRun, List.empty)
          h.awaitStatus() mustEqual 1
          h.stderr() must contain("Boom!")
        }

        "warn on global runtime collision" in {
          val h = platform(GlobalRacingInit, List.empty)
          h.awaitStatus() mustEqual 0
          h.stderr() must contain(
            "Cats Effect global runtime already initialized; custom configurations will be ignored")
          h.stderr() must not(contain("boom"))
        }

        "reset global runtime on shutdown" in {
          val h = platform(GlobalShutdown, List.empty)
          h.awaitStatus() mustEqual 0
          h.stderr() must not contain
            "Cats Effect global runtime already initialized; custom configurations will be ignored"
          h.stderr() must not(contain("boom"))
        }

        "warn on cpu starvation" in {
          val h = platform(CpuStarvation, List.empty)
          h.awaitStatus()
          val err = h.stderr()
          err must not(contain("[WARNING] Failed to register Cats Effect CPU"))
          err must contain("[WARNING] Your app's responsiveness")
          // we use a regex because time has too many corner cases - a test run at just the wrong
          // moment on new year's eve, etc
          err must beMatching(
            // (?s) allows matching across line breaks
            """(?s)^\d{4}-[01]\d-[0-3]\dT[012]\d:[0-6]\d:[0-6]\d(?:\.\d{1,3})?Z \[WARNING\] Your app's responsiveness.*"""
          )
        }

        "custom runtime installed as global" in {
          val h = platform(CustomRuntime, List.empty)
          h.awaitStatus() mustEqual 0
        }

        "abort awaiting shutdown hooks" in {
          val h = platform(ShutdownHookImmediateTimeout, List.empty)
          h.awaitStatus() mustEqual 0
        }

        "run finalizers on TERM" in {
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

      "exit on fatal error without IOApp" in {
        val h = platform(FatalErrorRaw, List.empty)
        h.awaitStatus()
        h.stdout() must not(contain("sadness"))
        h.stderr() must not(contain("Promise already completed"))
      }

      "exit on canceled" in {
        val h = platform(Canceled, List.empty)
        h.awaitStatus() mustEqual 1
      }

      if (BuildInfo.testJSIOApp) {
        "gracefully ignore undefined process.exit" in {
          val h = platform(UndefinedProcessExit, List.empty)
          h.awaitStatus() mustEqual 0
        }

        "support main thread evaluation" in skipped(
          "JavaScript is all main thread, all the time")

      } else {
        val isJava8 = sys.props.get("java.version").filter(_.startsWith("1.8")).isDefined

        if (isJava8) {
          "live fiber snapshot" in skipped(
            "JDK 8 does not have free signals for live fiber snapshots")
        } else if (isWindows) {
          "live fiber snapshot" in skipped(
            "cannot observe signals sent to process termination on Windows")
        } else {
          "live fiber snapshot" in {
            val h = platform(LiveFiberSnapshot, List.empty)

            // wait for the application to fully start before trying to send the signal
            while (!h.stdout().contains("ready")) {
              Thread.sleep(100L)
            }

            val pid = h.pid()
            pid must beSome
            pid.foreach(platform.sendSignal)
            h.awaitStatus()
            val stderr = h.stderr()
            stderr must contain("cats.effect.IOFiber")
          }
        }

        "shutdown on worker thread interruption" in {
          val h = platform(WorkerThreadInterrupt, List.empty)
          h.awaitStatus() mustEqual 1
          h.stderr() must contain("java.lang.InterruptedException")
          ok
        }

        "support main thread evaluation" in {
          val h = platform(EvalOnMainThread, List.empty)
          h.awaitStatus() mustEqual 0
        }

        "use configurable reportFailure for MainThread" in {
          val h = platform(MainThreadReportFailure, List.empty)
          h.awaitStatus() mustEqual 0
        }

        "warn on blocked threads" in {
          val h = platform(BlockedThreads, List.empty)
          h.awaitStatus()
          val err = h.stderr()
          err must contain(
            "[WARNING] A Cats Effect worker thread was detected to be in a blocked state")
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
