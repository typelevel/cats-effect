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

import cats.{Eq, Order, Show}
import cats.data.{EitherT, Ior, IorT, Kleisli, OptionT, WriterT}
//import cats.laws.discipline.{AlignTests, ParallelTests}
import cats.laws.discipline.arbitrary._
import cats.laws.discipline.MiniInt
import cats.implicits._
//import cats.effect.kernel.ParallelF
import cats.effect.laws.TemporalTests
import cats.effect.testkit._
import cats.effect.testkit.TimeT._
import cats.effect.testkit.{pure, PureConcGenerators}, pure._

// import org.scalacheck.rng.Seed
import org.scalacheck.util.Pretty
import org.scalacheck.{Arbitrary, Cogen, Gen, Prop}

import org.specs2.ScalaCheck
// import org.specs2.scalacheck.Parameters
import org.specs2.mutable._

import scala.concurrent.duration._

import org.typelevel.discipline.specs2.mutable.Discipline

import java.util.concurrent.TimeUnit

class PureConcSpec extends Specification with Discipline with ScalaCheck {
  import PureConcGenerators._
//  import ParallelFGenerators._

  implicit def prettyFromShow[A: Show](a: A): Pretty =
    Pretty.prettyString(a.show)

  implicit def kleisliEq[F[_], A, B](implicit ev: Eq[A => F[B]]): Eq[Kleisli[F, A, B]] =
    Eq.by[Kleisli[F, A, B], A => F[B]](_.run)

  implicit def exec(fb: TimeT[PureConc[Int, *], Boolean]): Prop =
    Prop(pure.run(TimeT.run(fb)).fold(false, _ => false, _.getOrElse(false)))

  implicit def execOptionT(sbool: OptionT[TimeT[PureConc[Int, *], *], Boolean]): Prop =
    Prop(
      pure
        .run(TimeT.run(sbool.value))
        .fold(
          false,
          _ => false,
          bO => bO.flatten.fold(false)(_ => true)
        ))

  implicit def execEitherT[E](sbool: EitherT[TimeT[PureConc[Int, *], *], E, Boolean]): Prop =
    Prop(
      pure
        .run(TimeT.run(sbool.value))
        .fold(false, _ => false, bO => bO.fold(false)(e => e.fold(_ => false, b => b))))

  implicit def orderWriterT[F[_], S, A](
      implicit Ord: Order[F[(S, A)]]): Order[WriterT[F, S, A]] = Order.by(_.run)

  implicit def execWriterT[S](sbool: WriterT[TimeT[PureConc[Int, *], *], S, Boolean]): Prop =
    Prop(
      pure
        .run(TimeT.run(sbool.run))
        .fold(
          false,
          _ => false,
          pO => pO.fold(false)(p => p._2)
        )
    )

  implicit def orderIor[A, B](
      implicit A: Order[A],
      B: Order[B],
      AB: Order[(A, B)]): Order[Ior[A, B]] =
    new Order[Ior[A, B]] {

      override def compare(x: Ior[A, B], y: Ior[A, B]): Int =
        (x, y) match {
          case (Ior.Left(a1), Ior.Left(a2)) => A.compare(a1, a2)
          case (Ior.Left(_), _) => -1
          case (Ior.Both(a1, b1), Ior.Both(a2, b2)) => AB.compare((a1, b1), (a2, b2))
          case (Ior.Both(_, _), Ior.Left(_)) => 1
          case (Ior.Both(_, _), Ior.Right(_)) => -1
          case (Ior.Right(b1), Ior.Right(b2)) => B.compare(b1, b2)
          case (Ior.Right(_), _) => 1
        }

    }

  implicit def orderIorT[F[_], A, B](implicit Ord: Order[F[Ior[A, B]]]): Order[IorT[F, A, B]] =
    Order.by(_.value)

  implicit def execIorT[L](sbool: IorT[TimeT[PureConc[Int, *], *], L, Boolean]): Prop =
    Prop(
      pure
        .run(TimeT.run(sbool.value))
        .fold(
          false,
          _ => false,
          iO => iO.fold(false)(i => i.fold(_ => false, _ => true, (_, _) => false)))
    )

  //This is highly dubious
  implicit def orderKleisli[F[_], A](implicit Ord: Order[F[A]]): Order[Kleisli[F, MiniInt, A]] =
    Order.by(_.run(MiniInt.unsafeFromInt(0)))

  //This is highly dubious
  implicit def execKleisli(sbool: Kleisli[TimeT[PureConc[Int, *], *], MiniInt, Boolean]): Prop =
    Prop(
      pure
        .run(TimeT.run(sbool.run(MiniInt.unsafeFromInt(0))))
        .fold(false, _ => false, bO => bO.fold(false)(b => b))
    )

  implicit def arbPositiveFiniteDuration: Arbitrary[FiniteDuration] = {
    import TimeUnit._

    val genTU =
      Gen.oneOf(NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS)

    Arbitrary {
      genTU flatMap { u => Gen.posNum[Long].map(FiniteDuration(_, u)) }
    }
  }

  implicit def orderTimeT[F[_], A](implicit FA: Order[F[A]]): Order[TimeT[F, A]] =
    Order.by(TimeT.run(_))

  implicit def pureConcOrder[E: Order, A: Order]: Order[PureConc[E, A]] =
    Order.by(pure.run(_))

  implicit def cogenTime: Cogen[Time] =
    Cogen[FiniteDuration].contramap(_.now)

  implicit def arbTime: Arbitrary[Time] =
    Arbitrary(Arbitrary.arbitrary[FiniteDuration].map(new Time(_)))

  implicit def cogenKleisli[F[_], R, A](
      implicit cg: Cogen[R => F[A]]): Cogen[Kleisli[F, R, A]] =
    cg.contramap(_.run)

  checkAll(
    "TimeT[PureConc]",
    TemporalTests[TimeT[PureConc[Int, *], *], Int].temporal[Int, Int, Int](10.millis)
  ) /*(Parameters(seed = Some(Seed.fromBase64("OjD4TDlPxwCr-K-gZb-xyBOGeWMKx210V24VVhsJBLI=").get)))*/

  checkAll(
    "OptionT[TimeT[PureConc]]",
    TemporalTests[OptionT[TimeT[PureConc[Int, *], *], *], Int]
      .temporal[Int, Int, Int](10.millis)
  )

  checkAll(
    "EitherT[TimeT[PureConc]]",
    TemporalTests[EitherT[TimeT[PureConc[Int, *], *], Int, *], Int]
      .temporal[Int, Int, Int](10.millis)
  )

  checkAll(
    "IorT[PureConc]",
    TemporalTests[IorT[TimeT[PureConc[Int, *], *], Int, *], Int]
      .temporal[Int, Int, Int](10.millis)
  )

  checkAll(
    "Kleisli[PureConc]",
    TemporalTests[Kleisli[TimeT[PureConc[Int, *], *], MiniInt, *], Int]
      .temporal[Int, Int, Int](10.millis)
  )

  checkAll(
    "WriterT[PureConc]",
    TemporalTests[WriterT[TimeT[PureConc[Int, *], *], Int, *], Int]
      .temporal[Int, Int, Int](10.millis)
  )

//  checkAll("PureConc", ParallelTests[PureConc[Int, *]].parallel[Int, Int])

//  checkAll(
//    "ParallelF[PureConc]",
//    AlignTests[ParallelF[PureConc[Int, *], *]].align[Int, Int, Int, Int])
}
