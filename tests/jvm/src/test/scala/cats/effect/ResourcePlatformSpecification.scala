/*
 * Copyright 2020-2021 Typelevel
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

import cats.~>
import cats.syntax.eq._
import org.specs2.mutable.Specification

class ResourceJVMSpec extends Specification with Runners {

  "platform" should {
    def verifyThatSoeIsReproducibleWithStackDepth(stackDepth: Int): Unit = {
      def triggerStackOverflowError(n: Int): Int = {
        if (n <= 0) n
        else n + triggerStackOverflowError(n - 1)
      }

      try {
        triggerStackOverflowError(stackDepth)
        sys.error(
          s"expected a StackOverflowError from $stackDepth-deep recursion, consider increasing the depth in test"
        )
      } catch {
        case _: StackOverflowError =>
      }
    }

    "verify use is stack-safe over binds" in ticked { implicit ticker =>
      val stackDepth = 50000
      verifyThatSoeIsReproducibleWithStackDepth(stackDepth)
      val r = (1 to stackDepth)
        .foldLeft(Resource.eval(IO.unit)) {
          case (r, _) =>
            r.flatMap(_ => Resource.eval(IO.unit))
        }
        .use_
      r eqv IO.unit
    }

    "verify use is stack-safe over binds - 2" in real {
      val n = 50000
      verifyThatSoeIsReproducibleWithStackDepth(n)
      def p(i: Int = 0, n: Int = 50000): Resource[IO, Int] =
        Resource
          .pure {
            if (i < n) Left(i + 1)
            else Right(i)
          }
          .flatMap {
            case Left(a) => p(a)
            case Right(b) => Resource.pure(b)
          }

      p(n = n).use(IO.pure).mustEqual(n)
    }

    "verify mapK is stack-safe over binds" in ticked { implicit ticker =>
      val stackDepth = 50000
      verifyThatSoeIsReproducibleWithStackDepth(stackDepth)
      val r = (1 to stackDepth)
        .foldLeft(Resource.eval(IO.unit)) {
          case (r, _) =>
            r.flatMap(_ => Resource.eval(IO.unit))
        }
        .mapK {
          new ~>[IO, IO] {
            def apply[A](a: IO[A]): IO[A] = a
          }
        }
        .use_

      r eqv IO.unit
    }
  }
}
