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

package cats.effect

import scala.io.Source
import scala.sys.process.{BasicIO, Process, ProcessBuilder}

import java.io.File

import munit.FunSuite

class IOAppSuite extends FunSuite {

  abstract class Platform(val id: String) { outer =>
    def builder(proto: String, args: List[String]): ProcessBuilder
    def pid(proto: String): Option[Int]

    def dumpSignal: String

    def sendSignal(pid: Int): Unit = {
      Runtime.getRuntime().exec(s"kill -$dumpSignal $pid")
      ()
    }

    def apply(proto: String, args: List[String]): Handle = {
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
    val ClassPath = System.getProperty("catseffect.examples.classpath")

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

    def builder(proto: String, args: List[String]) = Process(
      s"$JavaHome/bin/java",
      List("-cp", ClassPath, s"catseffect.examples.$proto") ::: args)

    // scala.sys.process.Process and java.lang.Process lack getting PID support. Java 9+ introduced it but
    // whatever because it's very hard to obtain a java.lang.Process from scala.sys.process.Process.
    def pid(proto: String): Option[Int] = {
      val jpsStdoutBuffer = new StringBuffer()
      val jpsProcess =
        Process(s"$JavaHome/bin/jps", List.empty).run(BasicIO(false, jpsStdoutBuffer, None))
      jpsProcess.exitValue()

      val output = jpsStdoutBuffer.toString
      Source.fromString(output).getLines().find(_.contains(proto)).map(_.split(" ")(0).toInt)
    }

  }

  object Node extends Platform("node") {
    val dumpSignal = "USR2"

    def builder(proto: String, args: List[String]) =
      Process(
        s"node",
        "--enable-source-maps" :: BuildInfo
          .jsRunner
          .getAbsolutePath :: s"catseffect.examples.$proto" :: args)

    def pid(proto: String): Option[Int] = {
      val stdoutBuffer = new StringBuffer()
      val process =
        Process("ps", List("aux")).run(BasicIO(false, stdoutBuffer, None))
      process.exitValue()

      val output = stdoutBuffer.toString
      Source
        .fromString(output)
        .getLines()
        .find(l => l.contains(BuildInfo.jsRunner.getAbsolutePath) && l.contains(proto))
        .map(_.split(" +")(1).toInt)
    }
  }

  object Native extends Platform("native") {
    val dumpSignal = "USR1"

    def builder(proto: String, args: List[String]) =
      Process(BuildInfo.nativeRunner.getAbsolutePath, s"catseffect.examples.$proto" :: args)

    def pid(proto: String): Option[Int] = {
      val stdoutBuffer = new StringBuffer()
      val process =
        Process("ps", List("aux")).run(BasicIO(false, stdoutBuffer, None))
      process.exitValue()

      val output = stdoutBuffer.toString
      Source
        .fromString(output)
        .getLines()
        .find(l => l.contains(BuildInfo.nativeRunner.getAbsolutePath) && l.contains(proto))
        .map(_.split(" +")(1).toInt)
    }
  }

  lazy val platform = BuildInfo.platform match {
    case "jvm" => JVM
    case "js" => Node
    case "native" => Native
    case platform => throw new RuntimeException(s"unknown platform $platform")
  }

  lazy val isJava8 =
    platform == JVM && sys.props.get("java.version").filter(_.startsWith("1.8")).isDefined
  lazy val isWindows = System.getProperty("os.name").toLowerCase.contains("windows")

  {

    if (!isWindows) { // these tests have all been emperically flaky on Windows CI builds, so they're disabled

      test("evaluate and print hello world") {
        val h = platform("HelloWorld", Nil)
        assertEquals(h.awaitStatus(), 0)
        assertEquals(h.stdout(), s"Hello, World!${System.lineSeparator()}")
      }

      test("pass all arguments to child") {
        val expected = List("the", "quick", "brown", "fox jumped", "over")
        val h = platform("Arguments", expected)
        assertEquals(h.awaitStatus(), 0)
        assertEquals(
          h.stdout(),
          expected.mkString("", System.lineSeparator(), System.lineSeparator())
        )
      }

      test("exit on non-fatal error") {
        val h = platform("NonFatalError", List.empty)
        assertEquals(h.awaitStatus(), 1)
        assert(h.stderr().contains("Boom!"))
      }

      test("exit with leaked fibers") {
        val h = platform("LeakedFiber", List.empty)
        assertEquals(h.awaitStatus(), 0)
      }

      test("exit on fatal error") {
        val h = platform("FatalError", List.empty)
        assertEquals(h.awaitStatus(), 1)
        assert(h.stderr().contains("Boom!"))
        assert(!h.stdout().contains("sadness"))
      }

      test("exit on fatal error with other unsafe runs") {
        val h = platform("FatalErrorUnsafeRun", List.empty)
        assertEquals(h.awaitStatus(), 1)
        assert(h.stderr().contains("Boom!"))
      }

      test("exit on raising a fatal error with attempt") {
        val h = platform("RaiseFatalErrorAttempt", List.empty)
        assertEquals(h.awaitStatus(), 1)
        assert(h.stderr().contains("Boom!"))
        assert(!h.stdout().contains("sadness"))
      }

      test("exit on raising a fatal error with handleError") {
        val h = platform("RaiseFatalErrorHandle", List.empty)
        assertEquals(h.awaitStatus(), 1)
        assert(h.stderr().contains("Boom!"))
        assert(!h.stdout().contains("sadness"))
      }

      test("exit on raising a fatal error inside a map") {
        val h = platform("RaiseFatalErrorMap", List.empty)
        assertEquals(h.awaitStatus(), 1)
        assert(h.stderr().contains("Boom!"))
        assert(!h.stdout().contains("sadness"))
      }

      test("exit on raising a fatal error inside a flatMap") {
        val h = platform("RaiseFatalErrorFlatMap", List.empty)
        assertEquals(h.awaitStatus(), 1)
        assert(h.stderr().contains("Boom!"))
        assert(!h.stdout().contains("sadness"))
      }

      test("warn on global runtime collision") {
        val h = platform("GlobalRacingInit", List.empty)
        assertEquals(h.awaitStatus(), 0)
        assert(
          h.stderr()
            .contains(
              "Cats Effect global runtime already initialized; custom configurations will be ignored"))
        assert(!h.stderr().contains("boom"))
      }

      test("reset global runtime on shutdown") {
        val h = platform("GlobalShutdown", List.empty)
        assertEquals(h.awaitStatus(), 0)
        assert(
          !h.stderr()
            .contains(
              "Cats Effect global runtime already initialized; custom configurations will be ignored"))
        assert(!h.stderr().contains("boom"))
      }

      // TODO reenable this test (#3919)
      test("warn on cpu starvation".ignore) {
        val h = platform("CpuStarvation", List.empty)
        h.awaitStatus()
        val err = h.stderr()
        assert(!err.contains("[WARNING] Failed to register Cats Effect CPU"))
        assert(err.contains("[WARNING] Your app's responsiveness"))
        // we use a regex because time has too many corner cases - a test run at just the wrong
        // moment on new year's eve, etc
        assert(
          err.matches(
            // (?s) allows matching across line breaks
            """(?s)^\d{4}-[01]\d-[0-3]\dT[012]\d:[0-6]\d:[0-6]\d(?:\.\d{1,3})?Z \[WARNING\] Your app's responsiveness.*"""
          ))
      }

      test("custom runtime installed as global") {
        val h = platform("CustomRuntime", List.empty)
        assertEquals(h.awaitStatus(), 0)
      }

      if (platform != Native) {
        test("abort awaiting shutdown hooks") {
          val h = platform("ShutdownHookImmediateTimeout", List.empty)
          assertEquals(h.awaitStatus(), 0)
        }
        ()
      }
    }

    if (!isWindows) {
      // The jvm cannot gracefully terminate processes on Windows, so this
      // test cannot be carried out properly. Same for testing IOApp in sbt.

      test("run finalizers on TERM") {
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

        val h = platform("Finalizers", test.getAbsolutePath() :: Nil)

        var i = 0
        while (!h.stdout().contains("Started") && i < 100) {
          Thread.sleep(100)
          i += 1
        }

        Thread.sleep(
          100
        ) // give thread scheduling just a sec to catch up and get us into the latch.await()

        h.term()
        assertEquals(h.awaitStatus(), 143)

        i = 0
        while (readTest() == null && i < 100) {
          i += 1
        }
        assert(readTest().contains("canceled"))
      }
    } else ()

    test("exit on fatal error without IOApp") {
      val h = platform("FatalErrorRaw", List.empty)
      h.awaitStatus()
      assert(!h.stdout().contains("sadness"))
      assert(!h.stderr().contains("Promise already completed"))
    }

    test("exit on canceled") {
      val h = platform("Canceled", List.empty)
      assertEquals(h.awaitStatus(), 1)
    }

    if (!isJava8 && !isWindows && platform != Native) {
      // JDK 8 does not have free signals for live fiber snapshots
      // cannot observe signals sent to process termination on Windows
      test("live fiber snapshot") {
        val h = platform("LiveFiberSnapshot", List.empty)

        // wait for the application to fully start before trying to send the signal
        while (!h.stdout().contains("ready")) {
          Thread.sleep(100L)
        }

        val pid = h.pid()
        assert(pid.isDefined)
        pid.foreach(platform.sendSignal)
        h.awaitStatus()
        val stderr = h.stderr()
        assert(stderr.contains("cats.effect.IOFiber"))
      }
      ()
    }

    if (platform == JVM) {
      test("shutdown on worker thread interruption") {
        val h = platform("WorkerThreadInterrupt", List.empty)
        assertEquals(h.awaitStatus(), 1)
        assert(h.stderr().contains("java.lang.InterruptedException"))
      }

      test("support main thread evaluation") {
        val h = platform("EvalOnMainThread", List.empty)
        assertEquals(h.awaitStatus(), 0)
      }

      test("use configurable reportFailure for MainThread") {
        val h = platform("MainThreadReportFailure", List.empty)
        assertEquals(h.awaitStatus(), 0)
      }

      test("use configurable reportFailure for runnables on MainThread") {
        val h = platform("MainThreadReportFailureRunnable", List.empty)
        assertEquals(h.awaitStatus(), 0)
      }

      test("warn on blocked threads") {
        val h = platform("BlockedThreads", List.empty)
        h.awaitStatus()
        val err = h.stderr()
        assert(
          err.contains(
            "[WARNING] A Cats Effect worker thread was detected to be in a blocked state"))
      }

      test("shut down WSTP on fatal error without IOApp") {
        val h = platform("FatalErrorShutsDownRt", List.empty)
        h.awaitStatus()
        assert(!h.stdout().contains("sadness"))
        assert(h.stdout().contains("done"))
      }
    }

    if (platform == Node) {
      test("gracefully ignore undefined process.exit") {
        val h = platform("UndefinedProcessExit", List.empty)
        assertEquals(h.awaitStatus(), 0)
      }
    }

  }

  trait Handle {
    def awaitStatus(): Int
    def term(): Unit
    def stderr(): String
    def stdout(): String
    def pid(): Option[Int]
  }
}
