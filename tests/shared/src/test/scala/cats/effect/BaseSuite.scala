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

import cats.effect.testkit.TestContext

import org.scalacheck.Prop

import munit.{FunSuite, Location, ScalaCheckSuite, TestOptions}

trait BaseSuite extends FunSuite with Runners {

  def testUnit(name: String)(body: => Unit)(implicit loc: Location): Unit =
    test(name)(body)

  def testUnit(options: TestOptions)(body: => Unit)(implicit loc: Location): Unit =
    test(options)(body)

  override def munitValueTransforms: List[ValueTransform] =
    super.munitValueTransforms ++ List(
      new ValueTransform("IO", { case _: IO[?] => sys.error("Non-evaluated IO") }),
      new ValueTransform("SyncIO", { case _: SyncIO[?] => sys.error("Non-evaluated SyncIO") }),
      new ValueTransform(
        "Prop",
        {
          case _: Prop if !munitTestTransforms.exists(_.name == "ScalaCheck Prop") =>
            sys.error("Non-evaluated Prop.")
        }
      ),
      new ValueTransform(
        "Unexpected test result type",
        {
          case r if !r.isInstanceOf[Unit] && !r.isInstanceOf[Prop] =>
            sys.error(s"Unexpected value of type ${r.getClass.getName}: $r")
        }
      )
    )

}

trait BaseScalaCheckSuite extends BaseSuite with ScalaCheckSuite {
  def tickedProperty(options: TestOptions)(body: Ticker => Prop)(implicit loc: Location): Unit =
    test(options)(body(Ticker(TestContext())))
}
