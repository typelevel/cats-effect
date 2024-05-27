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

class IOImplicitSpec extends BaseSpec {

  "Can resolve IO sequence ops without import of cats.syntax.all" in { // compilation test
    for {
      _ <- List(IO(1)).sequence_
      _ <- Option(IO(1)).sequence
      _ <- Option(IO(1)).sequence_
      _ <- List(IO(List(1))).flatSequence
    } yield ()
    true
  }

  "Can resolve IO.Par ops without import of cats.syntax.all" in { // compilation test
    for {
      _ <- Option(IO(1)).parSequence
      _ <- Option(IO(1)).parSequence_
      _ <- IO(1).parReplicateA(2)
      _ <- IO(1).parReplicateA_(2)
      _ <- IO(1).parProduct(IO(2))
      _ <- IO(1).parProductL(IO(2))
      _ <- IO(1).parProductR(IO(2))
      _ <- List(IO(Option(1))).parSequenceFilter
      _ <- List(IO(1)).parUnorderedSequence
      _ <- List(IO(List(1))).parFlatSequence
      _ <- List(IO(List(1))).parUnorderedFlatSequence
      _ <- (IO(1), IO(2)).parMapN(_ + _)
      _ <- (IO(1), IO(2)).parTupled
      _ <- (IO(1), IO(2)).parFlatMapN { case (x, y) => IO.pure(x + y) }
      _ <- (IO(1), IO(2), IO(3)).parMapN(_ + _ + _)
      _ <- (IO(1), IO(2), IO(3)).parTupled
      _ <- (IO(1), IO(2), IO(3)).parFlatMapN { case (x, y, z) => IO.pure(x + y + z) }
    } yield ()
    true
  }
}
