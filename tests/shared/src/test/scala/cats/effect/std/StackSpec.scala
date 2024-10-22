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

package cats
package effect
package std

import cats.arrow.FunctionK
import cats.syntax.all._

import org.specs2.specification.core.Fragments

import scala.concurrent.duration._

final class StackSpec extends BaseSpec with DetectPlatform {

  final override def executionTimeout = 2.minutes

  "ConcurrentStack" should {
    tests(Stack.apply[IO, Int])
  }

  "Stack with dual constructors" should {
    tests(Stack.in[IO, IO, Int])
  }

  "MapK'd Stack" should {
    tests(Stack[IO, Int].map(_.mapK[IO](FunctionK.id)))
  }

  def tests(stack: IO[Stack[IO, Int]]): Fragments = {
    "push and retrieve elements in LIFO order" in real {
      val p = for {
        s <- stack
        _ <- s.push(0)
        _ <- s.push(1)
        _ <- List.range(start = 2, end = 7).traverse_(n => s.push(n))
        _ <- s.pushN(7, 8, 9, 10)
        _ <- s.pushN(List.range(start = 11, end = 15): _*)
        result <- s.pop.replicateA(15)
      } yield result

      p mustEqual List.range(start = 0, end = 15)
    }

    "allow intercalate pushes and pops" in real {
      val p = for {
        s <- stack
        _ <- s.push(0)
        r1 <- s.pop
        _ <- s.push(1)
        _ <- s.push(3)
        _ <- s.push(5)
        r2 <- s.pop
        r3 <- s.pop
        _ <- s.push(10)
        r4 <- s.pop
        r5 <- s.pop
      } yield List(r1, r2, r3, r4, r5)

      p mustEqual List(0, 1, 3, 5, 10)
    }

    "block pop if empty" in ticked { implicit ticker =>
      val p = stack.flatMap(s => s.pop.void)

      p must nonTerminate
    }

    "unblock pop with a push" in ticked { implicit ticker =>
      val p = stack.flatMap { s =>
        (
          s.pop,
          (IO.sleep(1.millis) >> s.push(1))
        ).parTupled
      }

      p must completeAs((1, ()))
    }

    "blocked fibers must be released in FIFO order (multiple pushes)" in ticked {
      implicit ticker =>
        val numbers = List.range(start = 1, end = 10)
        val p = stack.flatMap { s =>
          val popAll = numbers.parTraverse { i => IO.sleep(i.millis) >> s.pop }

          val pushAll = IO.sleep(100.millis) >> numbers.traverse_(s.push)

          (popAll, pushAll).parTupled
        }

        p must completeAs((numbers, ()))
    }

    "blocked fibers must be released in FIFO order (pushN)" in ticked { implicit ticker =>
      val numbers = List.range(start = 1, end = 10)
      val p = stack.flatMap { s =>
        val popAll = numbers.parTraverse { i => IO.sleep(i.millis) >> s.pop }

        val pushAll = IO.sleep(100.millis) >> s.pushN(numbers: _*)

        (popAll, pushAll).parTupled
      }

      p must completeAs((numbers, ()))
    }

    "cancelling a blocked pop must remove it from waiting queue" in ticked { implicit ticker =>
      val p = for {
        s <- stack
        ref <- IO.ref(List.empty[Int])
        fiber = s.pop.flatMap(n => ref.update(n :: _)).start
        f1 <- fiber
        _ <- f1.cancel
        f2 <- fiber
        f3 <- fiber
        f4 <- fiber
        f5 <- fiber
        f6 <- fiber
        f7 <- fiber
        _ <- f2.cancel
        _ <- s.push(1)
        _ <- f3.join
        _ <- s.push(3)
        _ <- f4.join
        _ <- f6.cancel
        _ <- s.push(5)
        _ <- s.push(10)
        _ <- f5.join
        _ <- f7.join
        result <- ref.get
      } yield result

      p must completeAs(List(1, 3, 5, 10))
    }

    "tryPop must not block if empty" in real {
      val p = for {
        s <- stack
        r1 <- s.tryPop
        _ <- s.push(3)
        r2 <- s.tryPop
      } yield List(r1, r2)

      p mustEqual List(None, Some(3))
    }

    "peek must not block and must not remove the element" in real {
      val p = for {
        s <- stack
        r1 <- s.peek
        _ <- s.push(1)
        _ <- s.push(3)
        r2 <- s.peek
        r3 <- s.peek
        r4 <- s.tryPop
        r5 <- s.peek
      } yield List(r1, r2, r3, r4, r5)

      p mustEqual List(None, Some(3), Some(3), Some(3), Some(1))
    }

    "size must be consistent in a non concurrent scenario" in real {
      val p = for {
        s <- stack
        r1 <- s.size
        _ <- s.push(1)
        r2 <- s.size
        _ <- s.pushN(2, 3, 4, 5)
        r3 <- s.size
        _ <- s.pop
        _ <- s.pop
        r4 <- s.size
      } yield List(r1, r2, r3, r4)

      p mustEqual List(0, 1, 5, 3)
    }

    "used concurrently" in ticked { implicit ticker =>
      val numbers = List.range(start = 0, end = 10)
      val p = stack.flatMap { s =>
        (
          s.pop.parReplicateA(numbers.size).map(_.sorted),
          numbers.parTraverse_(s.push)
        ).parTupled
      }

      p must completeAs((numbers, ()))
    }

    "not lost elements when concurrently canceling a pop with a push" in real {
      val p = (stack, IO.deferred[Either[Int, Option[Int]]]).flatMapN {
        case (s, df) =>
          val left = IO.uncancelable(poll => poll(s.pop).flatMap(n => df.complete(Left(n))))
          val right = s.tryPop.flatMap(on => df.complete(Right(on)))

          left.start.flatMap { f =>
            (
              IO.sleep(10.millis) >> f.cancel,
              IO.sleep(10.millis) >> s.push(3)
            ).parTupled >> f.join
          } >> right >> df.get
      }

      List.fill(10000)(p).forallM { result =>
        result.map {
          case Left(5) => true
          case Right(Some(5)) => true
          case _ => false
        }
      }
    }
  }
}
