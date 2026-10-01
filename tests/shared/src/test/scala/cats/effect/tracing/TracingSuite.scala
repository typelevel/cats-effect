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

package cats.effect.tracing

import cats.effect.{Async, BaseSuite, IO}
import cats.effect.testkit.TestInstances

class TracingSuite extends BaseSuite with TestInstances {

  testUnit("IO.delay should generate identical traces") {
    val f = () => println("foo")
    val a = IO(f())
    val b = IO(f())
    (a, b) match {
      case (IO.Delay(_, eventA), IO.Delay(_, eventB)) => assert(eventA eq eventB)
      case _ => fail("expected IO.Delay")
    }
  }

  testUnit("IO.delay should generate unique traces") {
    val a = IO(println("foo"))
    val b = IO(println("bar"))
    (a, b) match {
      case (IO.Delay(_, eventA), IO.Delay(_, eventB)) => assert(eventA ne eventB)
      case _ => fail("expected IO.Delay")
    }
  }

  testUnit("Async.delay should generate identical traces") {
    val f = () => println("foo")
    val a = Async[IO].delay(f())
    val b = Async[IO].delay(f())
    (a, b) match {
      case (IO.Delay(_, eventA), IO.Delay(_, eventB)) => assert(eventA eq eventB)
      case _ => fail("expected IO.Delay")
    }
  }

  testUnit("Async.delay should generate unique traces") {
    val a = Async[IO].delay(println("foo"))
    val b = Async[IO].delay(println("bar"))
    (a, b) match {
      case (IO.Delay(_, eventA), IO.Delay(_, eventB)) => assert(eventA ne eventB)
      case _ => fail("expected IO.Delay")
    }
  }

  testUnit("IO.flatMap should generate identical traces") {
    val f = (_: Int) => IO.unit
    val a = IO.pure(1).flatMap(f)
    val b = IO.pure(2).flatMap(f)
    (a, b) match {
      case (IO.FlatMap(_, _, eventA), IO.FlatMap(_, _, eventB)) => assert(eventA eq eventB)
      case _ => fail("expected IO.FlatMap")
    }
  }

  testUnit("IO.flatMap should generate unique traces") {
    val a = IO.pure(1).flatMap(_ => IO.unit)
    val b = IO.pure(2).flatMap(_ => IO.never[Unit])
    (a, b) match {
      case (IO.FlatMap(_, _, eventA), IO.FlatMap(_, _, eventB)) => assert(eventA ne eventB)
      case _ => fail("expected IO.FlatMap")
    }
  }

  testUnit("Async.flatMap should generate identical traces") {
    val f = (_: Int) => IO.unit
    val a = Async[IO].flatMap(IO.pure(1))(f)
    val b = Async[IO].flatMap(IO.pure(2))(f)
    (a, b) match {
      case (IO.FlatMap(_, _, eventA), IO.FlatMap(_, _, eventB)) => assert(eventA eq eventB)
      case _ => fail("expected IO.FlatMap")
    }
  }

  testUnit("Async.flatMap should generate unique traces") {
    val a = Async[IO].flatMap(IO.pure(1))(_ => IO.unit)
    val b = Async[IO].flatMap(IO.pure(2))(_ => IO.never[Unit])
    (a, b) match {
      case (IO.FlatMap(_, _, eventA), IO.FlatMap(_, _, eventB)) => assert(eventA ne eventB)
      case _ => fail("expected IO.FlatMap")
    }
  }
}
