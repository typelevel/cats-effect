/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

import cats.data._
import cats.effect.laws.discipline._
import cats.effect.laws.discipline.arbitrary._
import cats.implicits._
import cats.laws.discipline.arbitrary._

class InstancesTests extends BaseTestsSuite {

  checkAllAsync("StateT[IO, S, ?]",
    implicit ec => {
      implicit val timer: Timer[StateT[IO, Int, ?]] = Timer.derive
      ConcurrentEffectTests[StateT[IO, Int, ?]].concurrentEffect[Int, Int, Int]
    })

  checkAllAsync("OptionT[IO, ?]",
    implicit ec => {
      implicit val timer: Timer[OptionT[IO, ?]] = Timer.derive
      ConcurrentTests[OptionT[IO, ?]].concurrent[Int, Int, Int]
    })

  checkAllAsync("Kleisli[IO, ?]",
    implicit ec => {
      implicit val timer: Timer[Kleisli[IO, Int, ?]] = Timer.derive
      ConcurrentTests[Kleisli[IO, Int, ?]].concurrent[Int, Int, Int]
    })
  checkAllAsync("Kleisli[IO, ?]",
    implicit ec => BracketTests[Kleisli[IO, Int, ?], Throwable].bracket[Int, Int, Int])

  checkAllAsync("EitherT[IO, Throwable, ?]",
    implicit ec => {
      implicit val timer: Timer[EitherT[IO, Throwable, ?]] = Timer.derive
      ConcurrentEffectTests[EitherT[IO, Throwable, ?]].concurrentEffect[Int, Int, Int]
    })

  checkAllAsync("WriterT[IO, Int, ?]",
    implicit ec => {
      implicit val timer: Timer[WriterT[IO, Int, ?]] = Timer.derive
      ConcurrentEffectTests[WriterT[IO, Int, ?]].concurrentEffect[Int, Int, Int]
    })

  implicit def keisliEq[F[_], R: Monoid, A](implicit FA: Eq[F[A]]): Eq[Kleisli[F, R, A]] =
    Eq.by(_.run(Monoid[R].empty))

  implicit def stateTEq[F[_]: FlatMap, S: Monoid, A](implicit FSA: Eq[F[(S, A)]]): Eq[StateT[F, S, A]] =
    Eq.by[StateT[F, S, A], F[(S, A)]](state => state.run(Monoid[S].empty))

}
