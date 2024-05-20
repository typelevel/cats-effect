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

import cats.syntax.all._

import org.scalacheck.Prop.forAll
import org.specs2.ScalaCheck

import scala.scalajs.js

trait IOPlatformSpecification { self: BaseSpec with ScalaCheck =>

  def platformSpecs =
    "platform" should {

      "round trip through js.Promise" in ticked { implicit ticker =>
        forAll { (ioa: IO[Int]) =>
          ioa.eqv(IO.fromPromise(IO(ioa.unsafeToPromise())))
        }.pendingUntilFixed // "callback scheduling gets in the way here since Promise doesn't use TestContext"
      }

      "round trip through js.Promise via Async" in ticked { implicit ticker =>
        def lossy[F[_]: Async, A](fa: F[A])(f: F[A] => js.Promise[A]): F[A] =
          Async[F].fromPromise(Sync[F].delay(f(fa))).map(x => x)

        forAll { (ioa: IO[Int]) =>
          ioa.eqv(lossy(ioa)(_.unsafeToPromise()))
        }.pendingUntilFixed // "callback scheduling gets in the way here since Promise doesn't use TestContext"
      }

      "realTimeDate should return a js.Date constructed from realTime" in ticked {
        implicit ticker =>
          val op = for {
            jsDate <- IO.realTimeDate
            realTime <- IO.realTime
          } yield jsDate.getTime().toLong == realTime.toMillis

          op must completeAs(true)
      }

    }
}
