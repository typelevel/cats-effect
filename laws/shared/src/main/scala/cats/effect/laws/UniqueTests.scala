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
package laws

import cats.effect.kernel.Unique

import org.scalacheck._
import org.typelevel.discipline.Laws

trait UniqueTests[F[_]] extends Laws {

  val laws: UniqueLaws[F]

  def unique(implicit exec: F[Boolean] => Prop): RuleSet = {
    new RuleSet {
      val name = "unique"
      val bases: Seq[(String, Laws#RuleSet)] = Nil
      val parents: Seq[RuleSet] = Seq()

      val props = Seq("uniqueness" -> exec(laws.uniqueness))
    }
  }
}

object UniqueTests {
  def apply[F[_]](implicit F0: Unique[F]): UniqueTests[F] =
    new UniqueTests[F] {
      val laws = UniqueLaws[F]
    }
}
