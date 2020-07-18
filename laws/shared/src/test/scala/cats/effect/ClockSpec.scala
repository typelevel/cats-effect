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

import cats.data._
import cats.{Eq, FlatMap, Monad, MonadError, Show}
import cats.effect.testkit.{freeEval, FreeSyncGenerators}, freeEval._
import cats.implicits._
import cats.laws.discipline.arbitrary._
import cats.effect.laws.ClockTests

import org.scalacheck.{Arbitrary, Gen, Prop}
import org.scalacheck.util.Pretty

import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

import org.typelevel.discipline.specs2.mutable.Discipline
import cats.laws.discipline.{eq, ExhaustiveCheck, MiniInt}; import eq._

class ClockSpec extends Specification with Discipline with ScalaCheck {
  import FreeSyncGenerators._

  implicit def prettyFromShow[A: Show](a: A): Pretty =
    Pretty.prettyString(a.show)

  implicit val eqThrowable: Eq[Throwable] =
    Eq.fromUniversalEquals

  implicit def kleisliEq[F[_], A, B](implicit ev: Eq[A => F[B]]): Eq[Kleisli[F, A, B]] =
    Eq.by[Kleisli[F, A, B], A => F[B]](_.run)

  implicit def indexedStateTEq[F[_], SA, SB, A](
      implicit SA: ExhaustiveCheck[SA],
      FSB: Eq[F[(SB, A)]],
      F: FlatMap[F]): Eq[IndexedStateT[F, SA, SB, A]] =
    Eq.by[IndexedStateT[F, SA, SB, A], SA => F[(SB, A)]](state => s => state.run(s))

  implicit def execOptionT(sbool: OptionT[FreeEitherSync, Boolean]): Prop =
    run(sbool.value).fold(
      Prop.exception(_),
      bO =>
        bO match {
          case None => Prop.falsified
          case Some(b) => if (b) Prop.proved else Prop.falsified
        })

  implicit def execEitherT[E](sbool: EitherT[FreeEitherSync, E, Boolean]): Prop =
    run(sbool.value).fold(
      Prop.exception(_),
      bE =>
        bE match {
          case Left(_) => Prop.falsified
          case Right(b) => if (b) Prop.proved else Prop.falsified
        })

  implicit def execStateT[S](sbool: StateT[FreeEitherSync, MiniInt, Boolean]): Prop =
    run(sbool.runF).fold(
      Prop.exception(_),
      f =>
        run((f(MiniInt.unsafeFromInt(0))))
          .fold(Prop.exception(_), b => if (b._2) Prop.proved else Prop.falsified)
    )

  implicit def execWriterT[S](sbool: WriterT[FreeEitherSync, S, Boolean]): Prop =
    run(sbool.run).fold(Prop.exception(_), b => if (b._2) Prop.proved else Prop.falsified)

  implicit def execIorT[L](sbool: IorT[FreeEitherSync, L, Boolean]): Prop =
    run(sbool.value).fold(
      Prop.exception(_),
      bO =>
        bO match {
          case Ior.Left(_) => Prop.falsified
          case Ior.Both(_, _) => Prop.falsified
          case Ior.Right(v) => if (v) Prop.proved else Prop.falsified
        })

  implicit def execKleisli(sbool: Kleisli[FreeEitherSync, MiniInt, Boolean]): Prop =
    run(sbool.run(MiniInt.unsafeFromInt(0)))
      .fold(Prop.exception(_), b => if (b) Prop.proved else Prop.falsified)

  implicit def execContT(sbool: ContT[FreeEitherSync, Int, Boolean]): Prop =
    run(
      sbool.run(b =>
        if (b) Monad[FreeEitherSync].pure(1)
        else MonadError[FreeEitherSync, Throwable].raiseError(new RuntimeException))
    ).fold(Prop.exception(_), _ => Prop.proved)

  implicit def execReaderWriterStateT(
      sbool: ReaderWriterStateT[FreeEitherSync, MiniInt, Int, MiniInt, Boolean]): Prop =
    run(sbool.runF).fold(
      Prop.exception(_),
      f => {
        val s = f(MiniInt.unsafeFromInt(0), MiniInt.unsafeFromInt(0))
        val t = run(s)
        t.fold(Prop.exception(_), u => if (u._3) Prop.proved else Prop.falsified)
      }
    )

  implicit def arbContT[M[_], A, B](
      implicit arbFn: Arbitrary[(B => M[A]) => M[A]]): Arbitrary[ContT[M, A, B]] =
    Arbitrary(arbFn.arbitrary.map(ContT[M, A, B](_)))

  //Shamelessly stolen from https://github.com/typelevel/cats/blob/master/tests/src/test/scala/cats/tests/ContTSuite.scala
  implicit def eqContT[M[_], A, B](
      implicit arbFn: Arbitrary[B => M[A]],
      eqMA: Eq[M[A]]): Eq[ContT[M, A, B]] = {
    val genItems = Gen.listOfN(100, arbFn.arbitrary)
    val fns = genItems.sample.get
    new Eq[ContT[M, A, B]] {
      def eqv(a: ContT[M, A, B], b: ContT[M, A, B]) =
        fns.forall { fn => eqMA.eqv(a.run(fn), b.run(fn)) }
    }
  }

  //Shamelessly stolen from https://github.com/typelevel/cats/blob/master/tests/src/test/scala/cats/tests/IndexedReaderWriterStateTSuite.scala
  implicit def IRWSTEq[F[_], E, L, SA, SB, A](
      implicit SA: ExhaustiveCheck[SA],
      E: ExhaustiveCheck[E],
      FLSB: Eq[F[(L, SB, A)]],
      F: Monad[F]): Eq[IndexedReaderWriterStateT[F, E, L, SA, SB, A]] =
    Eq.by[IndexedReaderWriterStateT[F, E, L, SA, SB, A], (E, SA) => F[(L, SB, A)]] {
      state => (e, s) => state.run(e, s)
    }

  checkAll(
    "OptionT[FreeEitherSync, *]",
    ClockTests[OptionT[FreeEitherSync, *]].clock[Int, Int, Int])
  checkAll(
    "EitherT[FreeEitherSync, Int, *]",
    ClockTests[EitherT[FreeEitherSync, Int, *]].clock[Int, Int, Int])
  checkAll(
    "StateT[FreeEitherSync, MiniInt, *]",
    ClockTests[StateT[FreeEitherSync, MiniInt, *]].clock[Int, Int, Int])
  checkAll(
    "WriterT[FreeEitherSync, Int, *]",
    ClockTests[WriterT[FreeEitherSync, Int, *]].clock[Int, Int, Int])
  checkAll(
    "IorT[FreeEitherSync, Int, *]",
    ClockTests[IorT[FreeEitherSync, Int, *]].clock[Int, Int, Int])
  checkAll(
    "Kleisli[FreeEitherSync, MiniInt, *]",
    ClockTests[Kleisli[FreeEitherSync, MiniInt, *]].clock[Int, Int, Int])
  checkAll(
    "ContT[FreeEitherSync, Int, *]",
    ClockTests[ContT[FreeEitherSync, Int, *]].clock[Int, Int, Int])
  checkAll(
    "ReaderWriterStateT[FreeEitherSync, MiniInt, Int, MiniInt, *]",
    ClockTests[ReaderWriterStateT[FreeEitherSync, MiniInt, Int, MiniInt, *]]
      .clock[Int, Int, Int]
  )
}
