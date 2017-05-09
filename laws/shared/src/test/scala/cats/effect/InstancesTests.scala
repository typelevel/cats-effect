/*
 * Copyright 2017 Typelevel
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

import cats.data.StateT
import cats.effect.laws.discipline.{EffectTests, SyncTests}
import cats.effect.laws.util.TestContext
import cats.implicits._
import cats.laws.discipline.arbitrary._
import cats.laws.discipline.eq._

import org.scalacheck._
import org.scalacheck.rng.Seed

import scala.concurrent.Future
import scala.util.Try

class InstancesTests extends BaseTestsSuite {
  import Generators._

  checkAll("Eval", SyncTests[Eval].sync[Int, Int, Int])

  checkAllAsync("StateT[IO, S, ?]",
    implicit ec => EffectTests[StateT[IO, Int, ?]].effect[Int, Int, Int])

  // assume exceptions are equivalent if the exception name + message
  // match as strings.
  implicit def throwableEq: Eq[Throwable] =
    Eq[String].contramap((t: Throwable) => t.toString)

  // we want exceptions which occur during .value calls to be equal to
  // each other, assuming the exceptions seem equivalent.
  implicit def eqWithTry[A: Eq]: Eq[Eval[A]] =
    Eq[Try[A]].on((e: Eval[A]) => Try(e.value))

  implicit def arbitraryStateT[F[_], S, A](
    implicit
      arbFSA: Arbitrary[F[S => F[(S, A)]]]): Arbitrary[StateT[F, S, A]] =
    Arbitrary(arbFSA.arbitrary.map(StateT.applyF(_)))

  implicit def stateTEq[F[_], S, A](implicit S: Arbitrary[S], FSA: Eq[F[(S, A)]], F: FlatMap[F]): Eq[StateT[F, S, A]] =
    Eq.by[StateT[F, S, A], S => F[(S, A)]](state => s => state.run(s))

  implicit def cogenFuture[A](implicit ec: TestContext, cg: Cogen[Try[A]]): Cogen[Future[A]] = {
    Cogen { (seed: Seed, fa: Future[A] ) =>
      ec.tick()

      fa.value match {
        case None => seed
        case Some(ta) => cg.perturb(seed, ta)
      }
    }
  }
}
