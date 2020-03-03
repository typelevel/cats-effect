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

package cats
package effect

import cats.data._
import cats.effect.Sync._
import cats.effect.laws.discipline._
import cats.effect.laws.discipline.arbitrary._
import cats.implicits._
import cats.laws.discipline.arbitrary._

class InstancesTests extends BaseTestsSuite {
  checkAllAsync("StateT[IO, S, *]", implicit ec => AsyncTests[StateT[IO, Int, *]].async[Int, Int, Int])

  checkAllAsync(
    "StateT[IO, S, *]",
    implicit ec => {
      val fromState = λ[State[Int, *] ~> StateT[IO, Int, *]](st => StateT(s => IO.pure(st.run(s).value)))
      BracketTests[StateT[IO, Int, *], Throwable].bracketTrans[State[Int, *], Int, Int](fromState)
    }
  )

  checkAllAsync("OptionT[IO, *]", implicit ec => {
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    ConcurrentTests[OptionT[IO, *]].concurrent[Int, Int, Int]
  })

  checkAllAsync(
    "OptionT[IO, *]",
    implicit ec => {
      val fromOption = λ[Option ~> OptionT[IO, *]](OptionT.fromOption(_))
      BracketTests[OptionT[IO, *], Throwable].bracketTrans[Option, Int, Int](fromOption)
    }
  )

  checkAllAsync("Kleisli[IO, *]", implicit ec => {
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    ConcurrentTests[Kleisli[IO, Int, *]].concurrent[Int, Int, Int]
  })

  checkAllAsync("Kleisli[IO, *]", implicit ec => BracketTests[Kleisli[IO, Int, *], Throwable].bracket[Int, Int, Int])

  checkAllAsync(
    "EitherT[IO, Throwable, *]",
    implicit ec => {
      implicit val cs: ContextShift[IO] = ec.ioContextShift
      ConcurrentEffectTests[EitherT[IO, Throwable, *]].concurrentEffect[Int, Int, Int]
    }
  )

  checkAllAsync(
    "EitherT[IO, Throwable, *]",
    implicit ec => {
      val fromEither = λ[Either[Throwable, *] ~> EitherT[IO, Throwable, *]](EitherT.fromEither(_))
      BracketTests[EitherT[IO, Throwable, *], Throwable].bracketTrans[Either[Throwable, *], Int, Int](fromEither)
    }
  )

  checkAllAsync(
    "WriterT[IO, Int, *]",
    implicit ec => {
      implicit val cs: ContextShift[IO] = ec.ioContextShift
      ConcurrentEffectTests[WriterT[IO, Int, *]].concurrentEffect[Int, Int, Int]
    }
  )

  checkAllAsync(
    "WriterT[IO, Int, *]",
    implicit ec => {
      val fromWriter = λ[Writer[Int, *] ~> WriterT[IO, Int, *]](w => WriterT(IO.pure(w.run)))
      BracketTests[WriterT[IO, Int, *], Throwable].bracketTrans[Writer[Int, *], Int, Int](fromWriter)
    }
  )

  checkAllAsync("IorT[IO, Int, *]", implicit ec => {
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    ConcurrentTests[IorT[IO, Int, *]].concurrent[Int, Int, Int]
  })

  checkAllAsync(
    "IorT[IO, Int, *]",
    implicit ec => {
      val fromIor = λ[Ior[Int, *] ~> IorT[IO, Int, *]](IorT.fromIor(_))
      BracketTests[IorT[IO, Int, *], Throwable].bracketTrans[Ior[Int, *], Int, Int](fromIor)
    }
  )

  checkAllAsync("ReaderWriterStateT[IO, S, *]",
                implicit ec => AsyncTests[ReaderWriterStateT[IO, Int, Int, Int, *]].async[Int, Int, Int])

  checkAllAsync(
    "ReaderWriterStateT[IO, S, *]",
    implicit ec => {
      val fromReaderWriterState =
        λ[ReaderWriterState[Int, Int, Int, *] ~> ReaderWriterStateT[IO, Int, Int, Int, *]](st =>
          ReaderWriterStateT((e, s) => IO.pure(st.run(e, s).value))
        )
      BracketTests[ReaderWriterStateT[IO, Int, Int, Int, *], Throwable]
        .bracketTrans[ReaderWriterState[Int, Int, Int, *], Int, Int](fromReaderWriterState)
    }
  )

  implicit def kleisliEq[F[_], R: Monoid, A](implicit FA: Eq[F[A]]): Eq[Kleisli[F, R, A]] =
    Eq.by(_.run(Monoid[R].empty))

  implicit def stateTEq[F[_]: FlatMap, S: Monoid, A](implicit FSA: Eq[F[(S, A)]]): Eq[StateT[F, S, A]] =
    Eq.by[StateT[F, S, A], F[(S, A)]](state => state.run(Monoid[S].empty))

  implicit def readerWriterStateTEq[F[_]: Monad, E: Monoid, L, S: Monoid, A](
    implicit FLSA: Eq[F[(L, S, A)]]
  ): Eq[ReaderWriterStateT[F, E, L, S, A]] =
    Eq.by(_.run(Monoid[E].empty, Monoid[S].empty))
}
