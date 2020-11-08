/*
 * Copyright 2020 Typelevel
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
package local

class LocalSpec extends BaseSpec {
  
  "Local" should {
    "return a default value" in ticked { implicit ticker =>
      val io = Local.of(0).flatMap(_.get)

      io must completeAs(0)
    }

    "set and get a value" in ticked { implicit ticker =>
      val io = for {
        local <- Local.of(0)
        _ <- local.set(10)
        value <- local.get
      } yield value

      io must completeAs(10)
    }

    "preserve locals across async boundaries" in ticked { implicit ticker =>
      val io = for {
        local <- Local.of(0)
        _ <- local.set(10)
        _ <- IO.cede
        value <- local.get
      } yield value

      io must completeAs(10)
    }

    "copy locals to children fibers" in ticked { implicit ticker =>
      val io = for {
        local <- Local.of(0)
        _ <- local.set(10)
        f <- local.get.start
        value <- f.joinAndEmbedNever
      } yield value

      io must completeAs(10)
    }

    "child local manipulation is invisible to parents" in ticked { implicit ticker =>
      val io = for {
        local <- Local.of(10)
        f <- local.set(20).start
        _ <- f.join
        value <- local.get
      } yield value

      io must completeAs(10)
    }

    "parent local manipulation is invisible to children" in ticked { implicit ticker =>
      val io = for {
        local <- Local.of(0)
        d1 <- Deferred[IO, Unit]
        f <- (d1.get *> local.get).start
        _ <- local.set(10)
        _ <- d1.complete(())
        value <- f.joinAndEmbedNever
      } yield value

      io must completeAs(0)
    }
  }

}
