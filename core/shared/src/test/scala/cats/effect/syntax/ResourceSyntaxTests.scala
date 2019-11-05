/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats.effect.syntax

import cats.effect.Resource
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import resource._
import cats.instances.option._
class ResourceSyntaxTests extends AnyFunSuite with Matchers {

  test("F[A] liftToResources syntax") {
    Option(1).liftToResource: Resource[Option, Int]
  }

  test("F[Resource[F, A]] flattenToResource syntax") {
    Option(Resource.liftF(Option(1))).flattenToResource: Resource[Option, Int]
  }
}
