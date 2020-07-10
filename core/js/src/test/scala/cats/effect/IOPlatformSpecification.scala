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

package cats.effect

import cats.Eq
import cats.effect.testkit.TestContext
import cats.implicits._

import org.scalacheck.{Arbitrary, Cogen, Prop}, Prop.forAll

import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

abstract class IOPlatformSpecification extends Specification with ScalaCheck {

  def platformSpecs = {
    "round trip through js.Promise" in forAll { (ioa: IO[Int]) =>
      ioa eqv IO.fromPromise(IO(ioa.unsafeToPromise(unsafe.IORuntime(ctx, scheduler(), () => ()))))
    }.pendingUntilFixed // "callback scheduling gets in the way here since Promise doesn't use TestContext"
  }

  val ctx: TestContext
  def scheduler(): unsafe.Scheduler

  implicit def arbitraryIO[A: Arbitrary: Cogen]: Arbitrary[IO[A]]
  implicit def eqIOA[A: Eq]: Eq[IO[A]]
}
