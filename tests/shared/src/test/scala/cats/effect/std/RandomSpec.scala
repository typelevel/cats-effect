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
package std

import org.specs2.specification.core.Fragments

import scala.annotation.nowarn

class RandomSpec extends BaseSpec {

  "Random" should {
    "securely generate random bytes" in real {
      for {
        random1 <- Random.javaSecuritySecureRandom[IO]: @nowarn("cat=deprecation")
        bytes1 <- random1.nextBytes(128)
        random2 <- Random.javaSecuritySecureRandom[IO](2): @nowarn("cat=deprecation")
        bytes2 <- random2.nextBytes(256)
      } yield bytes1.length == 128 && bytes2.length == 256
    }

    "prevent array reference from leaking in ThreadLocalRandom.nextBytes impl" in real {
      val random = Random.javaUtilConcurrentThreadLocalRandom[IO]
      val nextBytes = random.nextBytes(128)
      for {
        bytes1 <- nextBytes
        bytes2 <- nextBytes
      } yield bytes1 ne bytes2
    }

    "prevent array reference from leaking in ScalaRandom.nextBytes impl" in real {
      for {
        random <- Random.scalaUtilRandom[IO]
        nextBytes = random.nextBytes(128)
        bytes1 <- nextBytes
        bytes2 <- nextBytes
      } yield bytes1 ne bytes2
    }

    "oneOf" >> {
      "return the only value provided" in real {
        for {
          random <- Random.scalaUtilRandom[IO]
          chosen <- random.oneOf(42)
        } yield chosen == 42
      }

      "eventually choose all the given values at least once" in real {
        val values = List(1, 2, 3, 4, 5)
        def chooseAndAccumulate(random: Random[IO], ref: Ref[IO, Set[Int]]): IO[Set[Int]] =
          random.oneOf(values.head, values.tail: _*).flatMap(x => ref.updateAndGet(_ + x))
        def haveChosenAllValues(ref: Ref[IO, Set[Int]]): IO[Boolean] =
          ref.get.map(_ == values.toSet)

        for {
          random <- Random.scalaUtilRandom[IO]
          ref <- Ref.of[IO, Set[Int]](Set.empty)
          _ <- chooseAndAccumulate(random, ref).untilM_(haveChosenAllValues(ref))
          success <- haveChosenAllValues(ref)
        } yield success
      }
    }

    elementOfTests[Int, List[Int]](
      "List",
      List.empty[Int],
      List(1, 2, 3, 4, 5)
    )

    elementOfTests[Int, Vector[Int]](
      "Vector",
      Vector.empty[Int],
      Vector(1, 2, 3, 4, 5)
    )

    elementOfTests[(String, Int), Map[String, Int]](
      "Map",
      Map.empty[String, Int],
      Map("a" -> 1, "b" -> 2, "c" -> 3, "d" -> 4, "e" -> 5)
    )

  }

  private def elementOfTests[A, C <: Iterable[A]](
      collectionType: String,
      emptyCollection: C,
      nonEmptyCollection: C
  ): Fragments = {

    s"elementOf ($collectionType)" >> {
      "reject an empty collection" in real {
        for {
          random <- Random.scalaUtilRandom[IO]
          result <- random.elementOf(emptyCollection).attempt
        } yield result.isLeft
      }

      "eventually choose all elements of the given collection at least once" in real {
        val xs = nonEmptyCollection
        def chooseAndAccumulate(random: Random[IO], ref: Ref[IO, Set[A]]): IO[Set[A]] =
          random.elementOf(xs).flatMap(x => ref.updateAndGet(_ + x))
        def haveChosenAllElements(ref: Ref[IO, Set[A]]): IO[Boolean] =
          ref.get.map(_ == xs.toSet)

        for {
          random <- Random.scalaUtilRandom[IO]
          ref <- Ref.of[IO, Set[A]](Set.empty)
          _ <- chooseAndAccumulate(random, ref).untilM_(haveChosenAllElements(ref))
          success <- haveChosenAllElements(ref)
        } yield success
      }
    }

  }

}
