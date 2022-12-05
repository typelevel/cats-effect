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

package cats
package effect

import cats.syntax.apply._

class IOLocalSpec extends BaseSpec {

  "IOLocal" should {
    "return a default value" in ticked { implicit ticker =>
      val io = IOLocal(0).flatMap(_.get)

      io must completeAs(0)
    }

    "set and get a value" in ticked { implicit ticker =>
      val io = for {
        local <- IOLocal(0)
        _ <- local.set(10)
        value <- local.get
      } yield value

      io must completeAs(10)
    }

    "preserve locals across async boundaries" in ticked { implicit ticker =>
      val io = for {
        local <- IOLocal(0)
        _ <- local.set(10)
        _ <- IO.cede
        value <- local.get
      } yield value

      io must completeAs(10)
    }

    "copy locals to children fibers" in ticked { implicit ticker =>
      val io = for {
        local <- IOLocal(0)
        _ <- local.set(10)
        f <- local.get.start
        value <- f.joinWithNever
      } yield value

      io must completeAs(10)
    }

    "child local manipulation is invisible to parents" in ticked { implicit ticker =>
      val io = for {
        local <- IOLocal(10)
        f <- local.set(20).start
        _ <- f.join
        value <- local.get
      } yield value

      io must completeAs(10)
    }

    "parent local manipulation is invisible to children" in ticked { implicit ticker =>
      val io = for {
        local <- IOLocal(0)
        d1 <- Deferred[IO, Unit]
        f <- (d1.get *> local.get).start
        _ <- local.set(10)
        _ <- d1.complete(())
        value <- f.joinWithNever
      } yield value

      io must completeAs(0)
    }

    "do not leak internal updates outside of a scope" in ticked { implicit ticker =>
      val io = for {
        local <- IOLocal(0)
        inside <- local.scope(1).surround(local.getAndSet(2))
        outside <- local.get
      } yield (inside, outside)

      io must completeAs((1, 0))
    }
  }

  "IOLocal.lens" should {
    final case class Lenses(base: IOLocal[(Int, String)], lens: IOLocal[Int])

    def create(iol: IO[IOLocal[(Int, String)]]): IO[Lenses] =
      iol.map(local => Lenses(local, local.lens(_._1)(p => i => (i, p._2))))

    "return a default value" in ticked { implicit ticker =>
      val io = create(IOLocal((0, ""))).flatMap(_.lens.get)

      io must completeAs(0)
    }

    "set and get a value" in ticked { implicit ticker =>
      val io = for {
        lenses <- create(IOLocal((0, "")))
        _ <- lenses.lens.set(10)
        baseValue <- lenses.base.get
        lensValue <- lenses.lens.get
      } yield (baseValue, lensValue)

      io must completeAs((10, "") -> 10)
    }

    "preserve locals across async boundaries" in ticked { implicit ticker =>
      val io = for {
        lenses <- create(IOLocal((0, "")))
        _ <- lenses.lens.set(10)
        _ <- IO.cede
        value <- lenses.lens.get
      } yield value

      io must completeAs(10)
    }

    "copy locals to children fibers" in ticked { implicit ticker =>
      val io = for {
        lenses <- create(IOLocal((0, "")))
        _ <- lenses.lens.set(10)
        f <- lenses.lens.get.start
        value <- f.joinWithNever
      } yield value

      io must completeAs(10)
    }

    "child local manipulation is invisible to parents" in ticked { implicit ticker =>
      val io = for {
        lenses <- create(IOLocal((10, "")))
        f <- lenses.lens.set(20).start
        _ <- f.join
        baseValue <- lenses.base.get
        lensValue <- lenses.lens.get
      } yield (baseValue, lensValue)

      io must completeAs((10, "") -> 10)
    }

    "parent local manipulation is invisible to children" in ticked { implicit ticker =>
      val io = for {
        lenses <- create(IOLocal((0, "")))
        d1 <- Deferred[IO, Unit]
        f <- (d1.get *> (lenses.base.get, lenses.lens.get).tupled).start
        _ <- lenses.lens.set(10)
        _ <- d1.complete(())
        value <- f.joinWithNever
      } yield value

      io must completeAs((0, "") -> 0)
    }

    "do not leak internal updates outside of a scope" in ticked { implicit ticker =>
      val io = for {
        lenses <- create(IOLocal((0, "")))
        inside <- lenses.lens.scope(1).surround(lenses.lens.getAndSet(2))
        baseOutside <- lenses.base.get
        lensOutside <- lenses.lens.get
      } yield (inside, baseOutside, lensOutside)

      io must completeAs((1, (0, ""), 0))
    }
  }

}
