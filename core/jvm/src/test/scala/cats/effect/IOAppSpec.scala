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

package cats.effect

import cats.implicits._

import org.specs2.mutable.Specification

import scala.sys.process.{BasicIO, Process}

import java.io.File

class IOAppSpec extends Specification {

  val JavaHome = System.getProperty("java.home")
  val ClassPath = System.getProperty("sbt.classpath")

  "IOApp (jvm)" should {
    import examples._

    "evaluate and print hello world" in {
      val h = java(HelloWorld, Nil)
      h.awaitStatus() mustEqual 0
      h.stdout() mustEqual "Hello, World!\n"
    }

    "pass all arguments to child" in {
      val expected = List("the", "quick", "brown", "fox jumped", "over")
      val h = java(Arguments, expected)
      h.awaitStatus() mustEqual 0
      h.stdout() mustEqual expected.mkString("", "\n", "\n")
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

      val h = java(Finalizers, test.getAbsolutePath() :: Nil)

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

  def java(proto: IOApp, args: List[String]): Handle = {
    val stdoutBuffer = new StringBuffer()
    val builder = Process(
      s"${JavaHome}/bin/java",
      List("-cp", ClassPath, proto.getClass.getName.replaceAll("\\$$", "")) ::: args)
    val p = builder.run(BasicIO(false, stdoutBuffer, None))

    new Handle {
      def awaitStatus() = p.exitValue()
      def term() = p.destroy() // TODO probably doesn't work
      def stderr() = "" // TODO
      def stdout() = stdoutBuffer.toString
    }
  }

  trait Handle {
    def awaitStatus(): Int
    def term(): Unit
    def stderr(): String
    def stdout(): String
  }
}

package examples {

  object HelloWorld extends IOApp {
    def run(args: List[String]): IO[Int] =
      IO(println("Hello, World!")).as(0)
  }

  object Arguments extends IOApp {
    def run(args: List[String]): IO[Int] =
      args.traverse(s => IO(println(s))).as(0)
  }

  object Finalizers extends IOApp {
    import java.io.FileWriter

    def writeToFile(string: String, file: File): IO[Unit] =
      IO(new FileWriter(file)).bracket { writer => IO(writer.write(string)) }(writer =>
        IO(writer.close()))

    def run(args: List[String]): IO[Int] =
      (IO(println("Started")) >> IO.never)
        .onCancel(writeToFile("canceled", new File(args.head)))
        .as(0)
  }
}
