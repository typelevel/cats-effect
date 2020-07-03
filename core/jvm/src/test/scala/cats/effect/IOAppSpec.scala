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

import java.nio.file.Path

class IOAppSpec extends Specification {

  val JavaHome = System.getProperty("java.home")
  val ClassPath = System.getProperty("sbt.classpath")

  "IOApp" should {
    import examples._

    "evaluate and print hello world" in {
      val h = java(HelloWorld, Nil)
      h.awaitStatus() mustEqual 0
      h.stdout() mustEqual "Hello, World!\n"
    }
  }

  def java(proto: IOApp, args: List[String]): Handle = {
    val stdoutBuffer = new StringBuffer()
    val builder = Process(s"${JavaHome}/bin/java", List("-cp", ClassPath, proto.getClass.getName.replaceAll("\\$$", "")) ::: args)
    val p = builder.run(BasicIO(false, stdoutBuffer, None))

    new Handle {
      def awaitStatus() = p.exitValue()
      def term() = p.destroy()      // TODO probably doesn't work
      def stderr() = ""   // TODO
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
}
