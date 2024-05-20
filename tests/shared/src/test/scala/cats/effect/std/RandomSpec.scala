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
package std

import org.specs2.specification.core.Fragments

import scala.annotation.nowarn

class RandomSpec extends BaseSpec {
  "scala.util.Random" should {
    testRandom(Random.scalaUtilRandom[IO])
  }

  "java.util.Random" should {
    testRandom(Random.javaUtilRandom[IO](new java.util.Random(System.currentTimeMillis())))
  }

  "java.security.SecureRandom" should {
    testRandom(Random.javaSecuritySecureRandom[IO]): @nowarn("cat=deprecation")
  }

  "javaUtilConcurrentThreadLocalRandom" should {
    testRandom(IO.pure(Random.javaUtilConcurrentThreadLocalRandom[IO]))
  }

  /**
   * It verifies the correctness of generating random numbers and other random elements within
   * specified ranges and constraints.
   *
   * @param randomGen
   *   An IO-wrapped Random[IO] instance used for running random number generation tests
   */
  private def testRandom(randomGen: IO[Random[IO]]): Fragments = {
    "betweenDouble" >> {
      "generate a random double within a range" in real {
        val min: Double = 0.0
        val max: Double = 1.0
        val numIterations: Int = 1000
        for {
          random <- randomGen
          randDoubles <- random.betweenDouble(min, max).replicateA(numIterations)
        } yield randDoubles.forall(randDouble => randDouble >= min && randDouble <= max)
      }
    }

    "betweenFloat" >> {
      "generate a random float within a range" in real {
        val min: Float = 0.0f
        val max: Float = 1.0f
        val numIterations: Int = 1000
        for {
          random <- randomGen
          randFloats <- random.betweenFloat(min, max).replicateA(numIterations)
        } yield randFloats.forall(randFloat => randFloat >= min && randFloat <= max)
      }
    }

    "betweenInt" >> {
      "generate a random integer within a range" in real {
        val min: Integer = 0
        val max: Integer = 10
        val numIterations: Int = 1000
        for {
          random <- randomGen
          randInts <- random.betweenInt(min, max).replicateA(numIterations)
        } yield randInts.forall(randInt => randInt >= min && randInt <= max)
      }
    }

    "betweenLong" >> {
      "generate a random long within a range" in real {
        val min: Long = 0L
        val max: Long = 100L
        val numIterations: Int = 1000
        for {
          random <- randomGen
          randLongs <- random.betweenLong(min, max).replicateA(numIterations)
        } yield randLongs.forall(randLong => randLong >= min && randLong <= max)
      }
    }

    "nextAlphaNumeric" >> {
      "generate random alphanumeric characters" in real {
        val alphaNumeric: Set[Char] = (('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9')).toSet
        val numIterations: Int = 1000
        for {
          random <- randomGen
          randomChars <- random.nextAlphaNumeric.replicateA(numIterations)
        } yield randomChars.forall(randomChar => alphaNumeric.contains(randomChar))
      }
    }

    "nextBoolean" >> {
      "generate random boolean values" in real {
        for {
          random <- randomGen
          randomBoolean <- random.nextBoolean
        } yield randomBoolean must beOneOf(true, false)
      }
    }

    "nextBytes" >> {
      "securely generate random bytes" in real {
        for {
          random1 <- randomGen
          bytes1 <- random1.nextBytes(128)
          random2 <- randomGen
          bytes2 <- random2.nextBytes(256)
        } yield bytes1.length == 128 && bytes2.length == 256
      }

      "prevent array reference from leaking in ThreadLocalRandom.nextBytes impl" in real {
        randomGen.flatMap { random =>
          val nextBytes = random.nextBytes(128)
          for {
            bytes1 <- nextBytes
            bytes2 <- nextBytes
          } yield bytes1 ne bytes2
        }
      }

      "prevent array reference from leaking in ScalaRandom.nextBytes impl" in real {
        for {
          random <- randomGen
          nextBytes = random.nextBytes(128)
          bytes1 <- nextBytes
          bytes2 <- nextBytes
        } yield bytes1 ne bytes2
      }
    }

    "nextDouble" >> {
      "generate random double values between 0.0 and 1.0" in real {
        val numIterations: Int = 1000
        for {
          random <- randomGen
          randomDoubles <- random.nextDouble.replicateA(numIterations)
        } yield randomDoubles.forall(double => double >= 0.0 && double < 1.0)
      }
    }

    "nextFloat" >> {
      "generate random float values between 0.0 and 1.0" in real {
        val numIterations: Int = 1000
        for {
          random <- randomGen
          randomFloats <- random.nextFloat.replicateA(numIterations)
        } yield randomFloats.forall(float => float >= 0.0f && float < 1.0f)
      }
    }

    "nextGaussian" >> {
      "generate random Gaussian distributed double values with mean 0.0 and standard deviation 1.0" in real {
        val sampleSize = 1000
        for {
          random <- randomGen
          gaussians <- random.nextGaussian.replicateA(sampleSize)
          mean = gaussians.sum / sampleSize
          variance = gaussians.map(x => math.pow(x - mean, 2)).sum / sampleSize
          stddev = math.sqrt(variance)
        } yield java.lang.Double.isFinite(mean) && java.lang.Double.isFinite(stddev)
      }
    }

    "nextInt" >> {
      "generate random int value" in real {
        for {
          random <- randomGen
          int <- random.nextInt
        } yield int must beBetween(Int.MinValue, Int.MaxValue)
      }
    }

    "nextIntBounded" >> {
      "generate random int values within specified bounds" in real {
        val bound: Int = 100
        val numIterations: Int = 1000
        for {
          random <- randomGen
          randomInts <- random.nextIntBounded(bound).replicateA(numIterations)
        } yield randomInts.forall(int => int >= 0 && int < bound)
      }
    }

    "nextLong" >> {
      "generate random long value" in real {
        for {
          random <- randomGen
          long <- random.nextLong
        } yield long must beBetween(Long.MinValue, Long.MaxValue)
      }
    }

    "nextLongBounded" >> {
      "generate random long values within specified bounds" in real {
        val bound: Long = 100L
        val numIterations: Int = 1000
        for {
          random <- randomGen
          randomLongs <- random.nextLongBounded(bound).replicateA(numIterations)
        } yield randomLongs.forall(long => long >= 0L && long < bound)
      }
    }

    "nextPrintableChar" >> {
      "generate random printable characters" in real {
        val printableChars: Set[Char] = ((' ' to '~') ++ ('\u00A0' to '\u00FF')).toSet
        val numIterations: Int = 1000
        for {
          random <- randomGen
          randomChars <- random.nextPrintableChar.replicateA(numIterations)
        } yield randomChars.forall(char => printableChars.contains(char))
      }
    }

    "nextString" >> {
      "generate a random string with the specified length" in real {
        val length: Int = 100
        val numIterations: Int = 1000
        for {
          random <- randomGen
          randomStrings <- random.nextString(length).replicateA(numIterations)
        } yield {
          randomStrings.forall(_.length == length)
        }
      }
    }

    "shuffleList" >> {
      "shuffle a list" in real {
        val sampleSize: Int =
          10000 // In case of modification, consider the probability of error
        val list: List[Int] = (1 to sampleSize).toList
        for {
          random <- randomGen
          shuffled <- random.shuffleList(list)
        } yield shuffled != list && shuffled.sorted == list.sorted
      }
    }

    "shuffleVector" >> {
      "shuffle a vector" in real {
        val sampleSize: Int = 10000
        val vector: Vector[Int] = (1 to sampleSize).toVector
        for {
          random <- randomGen
          shuffled <- random.shuffleVector(vector)
        } yield shuffled != vector && shuffled.sorted == vector.sorted
      }
    }

    "oneOf" >> {
      "return the only value provided" in real {
        for {
          random <- randomGen
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
          random <- randomGen
          ref <- Ref.of[IO, Set[Int]](Set.empty)
          _ <- chooseAndAccumulate(random, ref).untilM_(haveChosenAllValues(ref))
          success <- haveChosenAllValues(ref)
        } yield success
      }

      "not select any value outside the provided list" in real {
        val list: List[Int] = List(1, 2, 3, 4, 5)
        val numIterations: Int = 1000
        for {
          random <- randomGen
          chosenValues <- random.oneOf(list.head, list.tail: _*).replicateA(numIterations)
        } yield chosenValues.forall(list.contains)
      }
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
  ): Fragments = {

    s"elementOf ($collectionType)" >> {
      "reject an empty collection" in real {
        for {
          random <- randomGen
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
          random <- randomGen
          ref <- Ref.of[IO, Set[A]](Set.empty)
          _ <- chooseAndAccumulate(random, ref).untilM_(haveChosenAllElements(ref))
          success <- haveChosenAllElements(ref)
        } yield success
      }

      "not select any value outside the provided collection" in real {
        val numIterations: Int = 1000
        for {
          random <- randomGen
          chosenValues <- random.elementOf(nonEmptyCollection).replicateA(numIterations)
        } yield {
          val collectionVector: Vector[A] = nonEmptyCollection.toVector
          chosenValues.forall(collectionVector.contains(_))
        }
      }

    }

  }

}
