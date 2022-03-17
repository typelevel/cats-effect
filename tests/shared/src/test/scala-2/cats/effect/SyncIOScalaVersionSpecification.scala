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

package cats.effect

import cats.kernel.laws.SerializableLaws.serializable

import org.scalacheck.Prop.forAll
import org.typelevel.discipline.specs2.mutable.Discipline

// collapse this back into SyncIOSpec once we're on a release with lampepfl/dotty#14686
trait SyncIOScalaVersionSpecification extends BaseSpec with Discipline {
  def scalaVersionSpecs =
    "serialize" in {
      forAll { (io: SyncIO[Int]) => serializable(io) }
    }
}
