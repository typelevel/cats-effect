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
package std

import scala.annotation.nowarn

class RandomSuite extends BaseSuite {
  testRandom("scala.util.Random", Random.scalaUtilRandom[IO])

  testRandom(
    "java.util.Random",
    Random.javaUtilRandom[IO](new java.util.Random(System.currentTimeMillis())))

  testRandom("java.security.SecureRandom", Random.javaSecuritySecureRandom[IO]): @nowarn(
    "cat=deprecation")

  testRandom(
    "javaUtilConcurrentThreadLocalRandom",
    IO.pure(Random.javaUtilConcurrentThreadLocalRandom[IO]))

  /**
   * It verifies the correctness of generating random numbers and other random elements within
   * specified ranges and constraints.
   *
   * @param randomGen
   *   An IO-wrapped Random[IO] instance used for running random number generation tests
   */
  private def testRandom(name: String, randomGen: IO[Random[IO]]) = {
    real(s"$name - betweenDouble - generate a random double within a range") {
      val min: Double = 0.0
      val max: Double = 1.0
      val numIterations: Int = 1000
      for {
        random <- randomGen
        randDoubles <- random.betweenDouble(min, max).replicateA(numIterations)
      } yield assert(randDoubles.forall(randDouble => randDouble >= min && randDouble <= max))
    }

    real(s"$name - betweenDouble - handle overflow") {
      for {
        random <- randomGen
        randDoubles <- random.betweenDouble(Double.MinValue, Double.MaxValue).replicateA(100)
      } yield assert {
        randDoubles.forall { randDouble =>
          // this specific value means there was an unhandled overflow:
          randDouble != 1.7976931348623155e308
        }
      }
    }

    real(s"$name - betweenDouble - handle underflow") {
      for {
        random <- randomGen
        randDouble <- random.betweenDouble(
          Double.MinPositiveValue,
          java.lang.Math.nextUp(Double.MinPositiveValue))
      } yield assert(randDouble == Double.MinPositiveValue)
    }

    real(s"$name - betweenFloat - generate a random float within a range") {
      val min: Float = 0.0f
      val max: Float = 1.0f
      val numIterations: Int = 1000
      for {
        random <- randomGen
        randFloats <- random.betweenFloat(min, max).replicateA(numIterations)
      } yield assert(randFloats.forall(randFloat => randFloat >= min && randFloat <= max))
    }

    real(s"$name - betweenFloat - handle overflow") {
      for {
        random <- randomGen
        randFloats <- random.betweenFloat(Float.MinValue, Float.MaxValue).replicateA(100)
      } yield assert {
        randFloats.forall { randFloat =>
          // this specific value means there was an unhandled overflow:
          randFloat != 3.4028233e38f
        }
      }
    }

    real(s"$name - betweenFloat - handle underflow") {
      for {
        random <- randomGen
        randFloat <- random.betweenFloat(
          Float.MinPositiveValue,
          java.lang.Math.nextUp(Float.MinPositiveValue))
      } yield assert(randFloat == Float.MinPositiveValue)
    }

    real(s"$name - betweenInt - generate a random integer within a range") {
      val min: Integer = 0
      val max: Integer = 10
      val numIterations: Int = 1000
      for {
        random <- randomGen
        randInts <- random.betweenInt(min, max).replicateA(numIterations)
      } yield assert(randInts.forall(randInt => randInt >= min && randInt <= max))
    }

    real(s"$name - betweenLong - generate a random long within a range") {
      val min: Long = 0L
      val max: Long = 100L
      val numIterations: Int = 1000
      for {
        random <- randomGen
        randLongs <- random.betweenLong(min, max).replicateA(numIterations)
      } yield assert(randLongs.forall(randLong => randLong >= min && randLong <= max))
    }

    real(s"$name - nextAlphaNumeric - generate random alphanumeric characters") {
      val alphaNumeric: Set[Char] = (('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9')).toSet
      val numIterations: Int = 1000
      for {
        random <- randomGen
        randomChars <- random.nextAlphaNumeric.replicateA(numIterations)
      } yield assert(randomChars.forall(randomChar => alphaNumeric.contains(randomChar)))
    }

    real(s"$name - nextBoolean - generate random boolean values") {
      for {
        random <- randomGen
        randomBoolean <- random.nextBoolean
      } yield assert(randomBoolean || !randomBoolean)
    }

    real(s"$name - nextBytes - securely generate random bytes") {
      for {
        random1 <- randomGen
        bytes1 <- random1.nextBytes(128)
        random2 <- randomGen
        bytes2 <- random2.nextBytes(256)
      } yield assert(bytes1.length == 128 && bytes2.length == 256)
    }

    real(
      s"$name - nextBytes - prevent array reference from leaking in ThreadLocalRandom.nextBytes impl") {
      randomGen.flatMap { random =>
        val nextBytes = random.nextBytes(128)
        for {
          bytes1 <- nextBytes
          bytes2 <- nextBytes
        } yield assert(bytes1 ne bytes2)
      }
    }

    real(
      s"$name - nextBytes - prevent array reference from leaking in ScalaRandom.nextBytes impl") {
      for {
        random <- randomGen
        nextBytes = random.nextBytes(128)
        bytes1 <- nextBytes
        bytes2 <- nextBytes
      } yield assert(bytes1 ne bytes2)
    }

    real(s"$name - nextDouble - generate random double values between 0.0 and 1.0") {
      val numIterations: Int = 1000
      for {
        random <- randomGen
        randomDoubles <- random.nextDouble.replicateA(numIterations)
      } yield assert(randomDoubles.forall(double => double >= 0.0 && double < 1.0))
    }

    real(s"$name - nextFloat - generate random float values between 0.0 and 1.0") {
      val numIterations: Int = 1000
      for {
        random <- randomGen
        randomFloats <- random.nextFloat.replicateA(numIterations)
      } yield assert(randomFloats.forall(float => float >= 0.0f && float < 1.0f))
    }

    real(
      s"$name - nextGaussian - generate random Gaussian distributed double values with mean 0.0 and standard deviation 1.0") {
      val sampleSize = 1000
      for {
        random <- randomGen
        gaussians <- random.nextGaussian.replicateA(sampleSize)
        mean = gaussians.sum / sampleSize
        variance = gaussians.map(x => math.pow(x - mean, 2)).sum / sampleSize
        stddev = math.sqrt(variance)
      } yield assert(java.lang.Double.isFinite(mean) && java.lang.Double.isFinite(stddev))
    }

    real(s"$name - nextInt - generate random int value") {
      for {
        random <- randomGen
        int <- random.nextInt
      } yield assert(int >= Int.MinValue && int <= Int.MaxValue)
    }

    real(s"$name - nextIntBounded - generate random int values within specified bounds") {
      val bound: Int = 100
      val numIterations: Int = 1000
      for {
        random <- randomGen
        randomInts <- random.nextIntBounded(bound).replicateA(numIterations)
      } yield assert(randomInts.forall(int => int >= 0 && int < bound))
    }

    real(s"$name - nextLong - generate random long value") {
      for {
        random <- randomGen
        long <- random.nextLong
      } yield assert(long >= Long.MinValue && long <= Long.MaxValue)
    }

    real(s"$name - nextLongBounded - generate random long values within specified bounds") {
      val bound: Long = 100L
      val numIterations: Int = 1000
      for {
        random <- randomGen
        randomLongs <- random.nextLongBounded(bound).replicateA(numIterations)
      } yield assert(randomLongs.forall(long => long >= 0L && long < bound))
    }

    real(s"$name - nextPrintableChar - generate random printable characters") {
      val printableChars: Set[Char] = ((' ' to '~') ++ ('\u00A0' to '\u00FF')).toSet
      val numIterations: Int = 1000
      for {
        random <- randomGen
        randomChars <- random.nextPrintableChar.replicateA(numIterations)
      } yield assert(randomChars.forall(char => printableChars.contains(char)))
    }

    real(s"$name - nextString - generate a random string with the specified length") {
      val length: Int = 100
      val numIterations: Int = 1000
      for {
        random <- randomGen
        randomStrings <- random.nextString(length).replicateA(numIterations)
      } yield assert(randomStrings.forall(_.length == length))
    }

    real(s"$name - shuffleList - shuffle a list") {
      val sampleSize: Int =
        10000 // In case of modification, consider the probability of error
      val list: List[Int] = (1 to sampleSize).toList
      for {
        random <- randomGen
        shuffled <- random.shuffleList(list)
      } yield assert(shuffled != list && shuffled.sorted == list.sorted)
    }

    real(s"$name - shuffleVector - shuffle a vector") {
      val sampleSize: Int = 10000
      val vector: Vector[Int] = (1 to sampleSize).toVector
      for {
        random <- randomGen
        shuffled <- random.shuffleVector(vector)
      } yield assert(shuffled != vector && shuffled.sorted == vector.sorted)
    }

    real(s"$name - oneOf - return the only value provided") {
      for {
        random <- randomGen
        chosen <- random.oneOf(42)
      } yield assertEquals(chosen, 42)
    }

    real(s"$name - oneOf - eventually choose all the given values at least once") {
      val values = List(1, 2, 3, 4, 5)
      def chooseAndAccumulate(random: Random[IO], ref: Ref[IO, Set[Int]]): IO[Set[Int]] =
        random.oneOf(values.head, values.tail: _*).flatMap(x => ref.updateAndGet(_ + x))
      def haveChosenAllValues(ref: Ref[IO, Set[Int]]): IO[Boolean] =
        ref.get.map(_ == values.toSet)

      for {
        random <- randomGen
        ref <- Ref.of[IO, Set[Int]](Set.empty)
        _ <- chooseAndAccumulate(random, ref).untilM_(haveChosenAllValues(ref))
        success <- haveChosenAllValues(ref)
      } yield assert(success)
    }

    real(s"$name - oneOf - not select any value outside the provided list") {
      val list: List[Int] = List(1, 2, 3, 4, 5)
      val numIterations: Int = 1000
      for {
        random <- randomGen
        chosenValues <- random.oneOf(list.head, list.tail: _*).replicateA(numIterations)
      } yield assert(chosenValues.forall(list.contains))
    }

    elementOfTests[Int, List[Int]](
      "List",
      List.empty[Int],
      List(1, 2, 3, 4, 5),
      randomGen
    )

    elementOfTests[Int, Set[Int]](
      "Set",
      Set.empty[Int],
      Set(1, 2, 3, 4, 5),
      randomGen
    )

    elementOfTests[Int, Vector[Int]](
      "Vector",
      Vector.empty[Int],
      Vector(1, 2, 3, 4, 5),
      randomGen
    )

    elementOfTests[(String, Int), Map[String, Int]](
      "Map",
      Map.empty[String, Int],
      Map("a" -> 1, "b" -> 2, "c" -> 3, "d" -> 4, "e" -> 5),
      randomGen
    )
  }

  /**
   * It verifies the correct behavior of randomly selecting elements from the given collection.
   *
   * @param collectionType
   *   A string describing the type of collection being tested (e.g., "List", "Set", "Vector")
   * @param emptyCollection
   *   An empty collection of the specified type to test empty collection handling
   * @param nonEmptyCollection
   *   A non-empty collection of the specified type containing elements to be tested
   * @param randomGen
   *   An IO-wrapped Random[IO] instance used for running the elementOf tests
   */
  private def elementOfTests[A, C <: Iterable[A]](
      collectionType: String,
      emptyCollection: C,
      nonEmptyCollection: C,
      randomGen: IO[Random[IO]]
  ) = {

    real(s"elementOf ($collectionType) - reject an empty collection") {
      for {
        random <- randomGen
        result <- random.elementOf(emptyCollection).attempt
      } yield assert(result.isLeft)
    }

    real(
      s"elementOf ($collectionType) - eventually choose all elements of the given collection at least once") {
      val xs = nonEmptyCollection
      def chooseAndAccumulate(random: Random[IO], ref: Ref[IO, Set[A]]): IO[Set[A]] =
        random.elementOf(xs).flatMap(x => ref.updateAndGet(_ + x))
      def haveChosenAllElements(ref: Ref[IO, Set[A]]): IO[Boolean] =
        ref.get.map(_ == xs.toSet)

      for {
        random <- randomGen
        ref <- Ref.of[IO, Set[A]](Set.empty)
        _ <- chooseAndAccumulate(random, ref).untilM_(haveChosenAllElements(ref))
        success <- haveChosenAllElements(ref)
      } yield assert(success)
    }

    real(
      s"elementOf ($collectionType) - not select any value outside the provided collection") {
      val numIterations: Int = 1000
      for {
        random <- randomGen
        chosenValues <- random.elementOf(nonEmptyCollection).replicateA(numIterations)
      } yield {
        val collectionVector: Vector[A] = nonEmptyCollection.toVector
        assert(chosenValues.forall(collectionVector.contains(_)))
      }
    }
  }

}
