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

import cats.data.{EitherT, Kleisli, OptionT, StateT, WriterT}
import cats.effect.laws.discipline.{AsyncTests, EffectTests, SyncTests}
import cats.effect.laws.util.TestContext
import cats.implicits._

// can't use underscore here because conflicting instances were added in cats master
// TODO re-wildcard it once we update to cats 1.0
import cats.laws.discipline.arbitrary.{
  catsLawsArbitraryForEitherT,
  catsLawsArbitraryForEval,
  catsLawsArbitraryForKleisli,
  catsLawsArbitraryForOptionT,
  catsLawsArbitraryForWriterT
}

import org.scalacheck._
import org.scalacheck.rng.Seed

import scala.concurrent.Future
import scala.util.Try

class InstancesTests extends BaseTestsSuite {
  import Generators._

  checkAll("EitherT[Eval, Throwable, ?]",
    SyncTests[EitherT[Eval, Throwable, ?]].sync[Int, Int, Int])

  checkAllAsync("StateT[IO, S, ?]",
    implicit ec => EffectTests[StateT[IO, Int, ?]].effect[Int, Int, Int])

  checkAllAsync("OptionT[IO, ?]",
    implicit ec => AsyncTests[OptionT[IO, ?]].async[Int, Int, Int])

  checkAllAsync("Kleisli[IO, Int, ?]",
    implicit ec => AsyncTests[Kleisli[IO, Int, ?]].async[Int, Int, Int])

  checkAllAsync("EitherT[IO, Throwable, ?]",
    implicit ec => EffectTests[EitherT[IO, Throwable, ?]].effect[Int, Int, Int])

  checkAllAsync("WriterT[IO, Int, ?]",
    implicit ec => EffectTests[WriterT[IO, Int, ?]].effect[Int, Int, Int])

  implicit def arbitraryStateT[F[_], S, A](
    implicit
      arbFSA: Arbitrary[F[S => F[(S, A)]]]): Arbitrary[StateT[F, S, A]] =
    Arbitrary(arbFSA.arbitrary.map(StateT.applyF(_)))

  implicit def keisliEq[F[_], R: Monoid, A](implicit FA: Eq[F[A]]): Eq[Kleisli[F, R, A]] =
    Eq.by(_.run(Monoid[R].empty))

  // for some reason, the function1Eq in cats causes spurious test failures?
  implicit def stateTEq[F[_]: FlatMap, S: Monoid, A](implicit FSA: Eq[F[(S, A)]]): Eq[StateT[F, S, A]] =
    Eq.by[StateT[F, S, A], F[(S, A)]](state => state.run(Monoid[S].empty))

  // this is required to avoid diverging implicit expansion issues on 2.10
  implicit def eitherTEq: Eq[EitherT[EitherT[Eval, Throwable, ?], Throwable, Int]] =
    Eq.by[EitherT[EitherT[Eval, Throwable, ?], Throwable, Int], EitherT[Eval, Throwable, Either[Throwable, Int]]](_.value)

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
