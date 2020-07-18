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

package cats.effect.testkit

import cats.{Applicative, ApplicativeError, Eq, Monad, MonadError}
import cats.effect.kernel._
import cats.implicits._

import org.scalacheck.{Arbitrary, Cogen, Gen}, Arbitrary.arbitrary

import scala.collection.immutable.SortedMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

trait GenK[F[_]] {
  def apply[A: Arbitrary: Cogen]: Gen[F[A]]
}

// Generators for * -> * kinded types
trait Generators1[F[_]] {
  protected val maxDepth: Int = 10

  //todo: uniqueness based on... names, I guess. Have to solve the diamond problem somehow

  //Generators of base cases, with no recursion
  protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[F[A]])]

  //Only recursive generators - the argument is a generator of the next level of depth
  protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[(String, Gen[F[A]])]

  //All generators possible at depth [[depth]]
  private def gen[A: Arbitrary: Cogen](depth: Int): Gen[F[A]] = {
    val genK: GenK[F] = new GenK[F] {
      def apply[B: Arbitrary: Cogen]: Gen[F[B]] = Gen.delay(gen(depth + 1))
    }

    val gens =
      if (depth > maxDepth) baseGen[A]
      else baseGen[A] ++ recursiveGen[A](genK)

    Gen.oneOf(SortedMap(gens: _*).map(_._2)).flatMap(identity)
  }

  //All generators possible at depth 0 - the only public method
  def generators[A: Arbitrary: Cogen]: Gen[F[A]] = gen[A](0)
}

//Applicative is the first place that lets us introduce values in the context, if we discount InvariantMonoidal
trait ApplicativeGenerators[F[_]] extends Generators1[F] {
  implicit val F: Applicative[F]

  protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[F[A]])] =
    List("pure" -> genPure[A])

  protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[(String, Gen[F[A]])] =
    List(
      "map" -> genMap[A](deeper),
      "ap" -> genAp[A](deeper)
    )

  private def genPure[A: Arbitrary]: Gen[F[A]] =
    arbitrary[A].map(_.pure[F])

  private def genMap[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    for {
      fa <- deeper[A]
      f <- Arbitrary.arbitrary[A => A]
    } yield F.map(fa)(f)

  private def genAp[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    for {
      fa <- deeper[A]
      ff <- deeper[A => A]
    } yield F.ap(ff)(fa)
}

trait MonadGenerators[F[_]] extends ApplicativeGenerators[F] {

  implicit val F: Monad[F]

  override protected def recursiveGen[A: Arbitrary: Cogen](
      deeper: GenK[F]): List[(String, Gen[F[A]])] =
    List(
      "flatMap" -> genFlatMap(deeper)
    ) ++ super.recursiveGen(deeper)

  private def genFlatMap[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    for {
      fa <- deeper[A]
      f <- Gen.function1[A, F[A]](deeper[A])
    } yield fa.flatMap(f)
}

trait ApplicativeErrorGenerators[F[_], E] extends ApplicativeGenerators[F] {
  implicit val arbitraryE: Arbitrary[E]
  implicit val cogenE: Cogen[E]

  implicit val F: ApplicativeError[F, E]

  override protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[F[A]])] =
    List(
      "raiseError" -> genRaiseError[A]
    ) ++ super.baseGen[A]

  override protected def recursiveGen[A](
      deeper: GenK[F])(implicit AA: Arbitrary[A], AC: Cogen[A]): List[(String, Gen[F[A]])] =
    List(
      "handleErrorWith" -> genHandleErrorWith[A](deeper)(AA, AC)
    ) ++ super.recursiveGen(deeper)(AA, AC)

  private def genRaiseError[A]: Gen[F[A]] =
    arbitrary[E].map(F.raiseError[A](_))

  private def genHandleErrorWith[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    for {
      fa <- deeper[A]
      f <- Gen.function1[E, F[A]](deeper[A])
    } yield F.handleErrorWith(fa)(f)
}

trait MonadErrorGenerators[F[_], E]
    extends MonadGenerators[F]
    with ApplicativeErrorGenerators[F, E] {
  implicit val F: MonadError[F, E]
}

trait ClockGenerators[F[_]] extends ApplicativeGenerators[F] {
  implicit val F: Clock[F]

  implicit protected val arbitraryFD: Arbitrary[FiniteDuration]

  override protected def baseGen[A: Arbitrary: Cogen] =
    List("monotonic" -> genMonotonic[A], "realTime" -> genRealTime[A]) ++ super.baseGen[A]

  private def genMonotonic[A: Arbitrary] =
    arbitrary[A].map(F.monotonic.as(_))

  private def genRealTime[A: Arbitrary] =
    arbitrary[A].map(F.realTime.as(_))
}

trait SyncGenerators[F[_]] extends MonadErrorGenerators[F, Throwable] with ClockGenerators[F] {
  implicit val F: Sync[F]

  override protected def baseGen[A: Arbitrary: Cogen] =
    ("delay" -> arbitrary[A].map(F.delay(_))) :: super.baseGen[A]
}

trait ConcurrentGenerators[F[_], E] extends MonadErrorGenerators[F, E] {
  implicit val F: Concurrent[F, E]

  override protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[F[A]])] =
    List(
      "canceled" -> genCanceled[A],
      "cede" -> genCede[A],
      "never" -> genNever[A]
    ) ++ super.baseGen[A]

  override protected def recursiveGen[A](
      deeper: GenK[F])(implicit AA: Arbitrary[A], AC: Cogen[A]): List[(String, Gen[F[A]])] =
    List(
      "uncancelable" -> genUncancelable[A](deeper),
      "racePair" -> genRacePair[A](deeper),
      "start" -> genStart[A](deeper),
      "join" -> genJoin[A](deeper),
      "onCancel" -> genOnCancel[A](deeper)
    ) ++ super.recursiveGen(deeper)(AA, AC)

  private def genCanceled[A: Arbitrary]: Gen[F[A]] =
    arbitrary[A].map(F.canceled.as(_))

  private def genCede[A: Arbitrary]: Gen[F[A]] =
    arbitrary[A].map(F.cede.as(_))

  private def genNever[A]: Gen[F[A]] =
    F.never[A]

  // TODO we can't really use poll :-( since we can't Cogen FunctionK
  private def genUncancelable[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    deeper[A].map(pc => F.uncancelable(_ => pc))

  private def genStart[A: Arbitrary](deeper: GenK[F]): Gen[F[A]] =
    deeper[Unit].flatMap(pc => arbitrary[A].map(a => F.start(pc).as(a)))

  private def genJoin[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    for {
      fiber <- deeper[A]
      cont <- deeper[Unit]
      a <- arbitrary[A]
    } yield F.start(fiber).flatMap(f => cont >> f.join).as(a)

  private def genOnCancel[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    for {
      fa <- deeper[A]
      fin <- deeper[Unit]
    } yield F.onCancel(fa, fin)

  private def genRacePair[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    for {
      fa <- deeper[A]
      fb <- deeper[A]

      cancel <- arbitrary[Boolean]

      back = F.racePair(fa, fb).flatMap {
        case Left((a, f)) =>
          if (cancel)
            f.cancel.as(a)
          else
            f.join.as(a)

        case Right((f, a)) =>
          if (cancel)
            f.cancel.as(a)
          else
            f.join.as(a)
      }
    } yield back
}

trait TemporalGenerators[F[_], E] extends ConcurrentGenerators[F, E] with ClockGenerators[F] {
  implicit val F: Temporal[F, E]

  override protected def baseGen[A: Arbitrary: Cogen] =
    ("sleep" -> genSleep[A]) :: super.baseGen[A]

  private def genSleep[A: Arbitrary] =
    for {
      t <- arbitraryFD.arbitrary
      a <- arbitrary[A]
    } yield F.sleep(t).as(a)
}

trait AsyncGenerators[F[_]] extends TemporalGenerators[F, Throwable] with SyncGenerators[F] {
  implicit val F: Async[F]

  implicit protected val arbitraryEC: Arbitrary[ExecutionContext]
  implicit protected val cogenFU: Cogen[F[Unit]]

  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]) =
    List("async" -> genAsync[A](deeper), "evalOn" -> genEvalOn[A](deeper)) ++ super
      .recursiveGen[A](deeper)

  private def genAsync[A: Arbitrary: Cogen](deeper: GenK[F]) =
    for {
      result <- arbitrary[Either[Throwable, A]]

      fo <- deeper[Option[F[Unit]]](
        Arbitrary(Gen.option[F[Unit]](deeper[Unit])),
        Cogen.cogenOption(cogenFU))
    } yield F.async[A](k => F.delay(k(result)) >> fo)

  private def genEvalOn[A: Arbitrary: Cogen](deeper: GenK[F]) =
    for {
      fa <- deeper[A]
      ec <- arbitraryEC.arbitrary
    } yield F.evalOn(fa, ec)
}

object ParallelFGenerators {
  implicit def arbitraryParallelF[F[_], A](
      implicit ArbF: Arbitrary[F[A]]): Arbitrary[ParallelF[F, A]] =
    Arbitrary {
      ArbF.arbitrary.map(f => ParallelF(f))
    }

  implicit def eqParallelF[F[_], A](implicit EqF: Eq[F[A]]): Eq[ParallelF[F, A]] =
    EqF.imap(ParallelF.apply)(ParallelF.value)
}

object OutcomeGenerators {
  def outcomeGenerators[F[_]: Applicative, E: Arbitrary: Cogen] =
    new ApplicativeErrorGenerators[Outcome[F, E, *], E] {
      val arbitraryE: Arbitrary[E] = implicitly
      val cogenE: Cogen[E] = implicitly
      implicit val F: ApplicativeError[Outcome[F, E, *], E] = Outcome.applicativeError[F, E]

      override protected def baseGen[A: Arbitrary: Cogen]
          : List[(String, Gen[Outcome[F, E, A]])] =
        List(
          "const(Canceled)" -> Gen.const(Outcome.Canceled[F, E, A]())
        ) ++ super.baseGen[A]
    }

  implicit def arbitraryOutcome[F[_]: Applicative, E: Arbitrary: Cogen, A: Arbitrary: Cogen]
      : Arbitrary[Outcome[F, E, A]] =
    Arbitrary {
      outcomeGenerators[F, E].generators[A]
    }

  implicit def cogenOutcome[F[_], E: Cogen, A](
      implicit A: Cogen[F[A]]): Cogen[Outcome[F, E, A]] =
    Cogen[Option[Either[E, F[A]]]].contramap {
      case Outcome.Canceled() => None
      case Outcome.Completed(fa) => Some(Right(fa))
      case Outcome.Errored(e) => Some(Left(e))
    }
}
