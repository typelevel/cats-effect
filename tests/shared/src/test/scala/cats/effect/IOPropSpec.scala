/*
 * Copyright 2020-2024 Typelevel
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

import cats.effect.implicits._
import cats.effect.std.Queue
import cats.syntax.all._

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

//We allow these tests to have a longer timeout than IOSpec as they run lots of iterations
class IOPropSpec extends BaseSpec with Discipline {

  override def executionTimeout: FiniteDuration = 2.minutes

  "io monad" should {

    "parTraverseN" should {
      "give the same result as parTraverse" in realProp(
        Gen.posNum[Int].flatMap(n => arbitrary[List[Int]].map(n -> _))) {
        case (n, l) =>
          val f: Int => IO[Int] = n => IO.pure(n + 1)

          l.parTraverse(f).flatMap { expected => l.parTraverseN(n)(f).mustEqual(expected) }
      }

      "never exceed the maximum bound of concurrent tasks" in realProp {
        for {
          length <- Gen.chooseNum(0, 50)
          limit <- Gen.chooseNum(1, 15, 2, 5)
        } yield length -> limit
      } {
        case (length, limit) =>
          Queue.unbounded[IO, Int].flatMap { q =>
            val task = q.offer(1) >> IO.sleep(7.millis) >> q.offer(-1)
            val testRun = List.fill(length)(task).parSequenceN(limit)
            def check(acc: Int = 0): IO[Unit] =
              q.tryTake.flatMap {
                case None => IO.unit
                case Some(n) =>
                  val newAcc = acc + n
                  if (newAcc > limit)
                    IO.raiseError(new Exception(s"Limit of $limit exceeded, was $newAcc"))
                  else check(newAcc)
              }

            testRun >> check().mustEqual(())
          }
      }
    }

    "parTraverseN_" should {

      "never exceed the maximum bound of concurrent tasks" in realProp {
        for {
          length <- Gen.chooseNum(0, 50)
          limit <- Gen.chooseNum(1, 15, 2, 5)
        } yield length -> limit
      } {
        case (length, limit) =>
          Queue.unbounded[IO, Int].flatMap { q =>
            val task = q.offer(1) >> IO.sleep(7.millis) >> q.offer(-1)
            val testRun = List.fill(length)(task).parSequenceN_(limit)
            def check(acc: Int = 0): IO[Unit] =
              q.tryTake.flatMap {
                case None => IO.unit
                case Some(n) =>
                  val newAcc = acc + n
                  if (newAcc > limit)
                    IO.raiseError(new Exception(s"Limit of $limit exceeded, was $newAcc"))
                  else check(newAcc)
              }

            testRun >> check().mustEqual(())
          }
      }
    }

    "parSequenceN" should {
      "give the same result as parSequence" in realProp(
        Gen.posNum[Int].flatMap(n => arbitrary[List[Int]].map(n -> _))) {
        case (n, l) =>
          l.map(IO.pure(_)).parSequence.flatMap { expected =>
            l.map(IO.pure(_)).parSequenceN(n).mustEqual(expected)
          }
      }
    }

    "parReplicateAN" should {
      "give the same result as replicateA" in realProp(
        for {
          n <- Gen.posNum[Int]
          replicas <- Gen.chooseNum(0, 50)
          value <- Gen.posNum[Int]
        } yield (n, replicas, value)
      ) {
        case (n, replicas, value) =>
          IO.pure(value).replicateA(replicas).flatMap { expected =>
            IO.pure(value).parReplicateAN(n)(replicas).mustEqual(expected)
          }
      }
    }
  }

}
