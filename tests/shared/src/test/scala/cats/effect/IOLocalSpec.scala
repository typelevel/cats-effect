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

import scala.annotation.tailrec

class IOLocalSpec extends BaseSpec {

  ioLocalTests(
    "IOLocal[Int]",
    (i: Int) => IOLocal(i).map(l => (l, l))
  )(0, 10, identity, identity)

  ioLocalTests(
    "IOLocal[(Int, String)].lens(_._1)(..)",
    (p: (Int, String)) =>
      IOLocal(p).map { l =>
        val lens = l.lens(_._1) { case (_, s) => i => (i, s) }
        (lens, lens)
      }
  )((0, ""), 10, _._1, identity)

  ioLocalTests(
    "IOLocal[(Int, String)].lens(_._1)(..) base",
    (p: (Int, String)) =>
      IOLocal(p).map { l =>
        val lens = l.lens(_._1) { case (_, s) => i => (i, s) }
        (lens, l)
      }
  )((0, ""), 10, identity, (_, ""))

  ioLocalTests(
    "IOLocal[(Int, String)].lens(_._1)(..) refracted",
    (p: (Int, String)) =>
      IOLocal(p).map { l =>
        val lens = l.lens(_._1) { case (_, s) => i => (i, s) }
        (l, lens)
      }
  )((0, ""), (10, "lorem"), _._1, _._1)

  "IOLocal.lens" should {
    "be stack safe" in ticked { implicit ticker =>
      @tailrec def stackLens(lens: IOLocal[Int], height: Int): IOLocal[Int] =
        if (height <= 0) lens
        else stackLens(lens.lens(_ + 1)((_: Int) => (y: Int) => y - 1), height - 1)

      val size = 16384
      val io = for {
        lens <- IOLocal(0)
        stack = stackLens(lens, size)
        d1 <- lens.get
        d2 <- stack.get
        _ <- stack.set(size)
        s2 <- stack.get
        s1 <- lens.get
        _ <- stack.update(_ / 2)
        u2 <- stack.get
        u1 <- lens.get
      } yield (d1, d2, s2, s1, u2, u1)

      io must completeAs((0, size, size, 0, size / 2, -size / 2))
    }
  }

  private def ioLocalTests[A, B, C: Eq: Show](
      name: String,
      localF: A => IO[(IOLocal[B], IOLocal[C])]
  )(
      initial: A,
      update: B,
      checkA: A => C,
      checkB: B => C
  ) = name should {
    "return a default value" in ticked { implicit ticker =>
      val io = localF(initial).flatMap(_._2.get)

      io must completeAs(checkA(initial))
    }

    "set and get a value" in ticked { implicit ticker =>
      val io = localF(initial).flatMap {
        case (writer, reader) =>
          for {
            _ <- writer.set(update)
            value <- reader.get
          } yield value
      }

      io must completeAs(checkB(update))
    }

    "preserve locals across async boundaries" in ticked { implicit ticker =>
      val io = localF(initial).flatMap {
        case (writer, reader) =>
          for {
            _ <- writer.set(update)
            _ <- IO.cede
            value <- reader.get
          } yield value
      }

      io must completeAs(checkB(update))
    }

    "copy locals to children fibers" in ticked { implicit ticker =>
      val io = localF(initial).flatMap {
        case (writer, reader) =>
          for {
            _ <- writer.set(update)
            f <- reader.get.start
            value <- f.joinWithNever
          } yield value
      }

      io must completeAs(checkB(update))
    }

    "child local manipulation is invisible to parents" in ticked { implicit ticker =>
      val io = localF(initial).flatMap {
        case (writer, reader) =>
          for {
            f <- writer.set(update).start
            _ <- f.join
            value <- reader.get
          } yield value
      }

      io must completeAs(checkA(initial))
    }

    "parent local manipulation is invisible to children" in ticked { implicit ticker =>
      val io = localF(initial).flatMap {
        case (writer, reader) =>
          for {
            d1 <- Deferred[IO, Unit]
            f <- (d1.get *> reader.get).start
            _ <- writer.set(update)
            _ <- d1.complete(())
            value <- f.joinWithNever
          } yield value
      }

      io must completeAs(checkA(initial))
    }

  }

}
