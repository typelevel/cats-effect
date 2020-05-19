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

package ce3

import cats.Show
import cats.implicits._

import org.scalacheck.{Arbitrary, Cogen, Gen}, Arbitrary.arbitrary
import cats.~>
import cats.Monad
import cats.Applicative
import cats.Functor
import cats.ApplicativeError
import scala.collection.immutable.SortedMap
import cats.MonadError
import ce3.laws.CogenK
import ce3.laws.ResourceGenerators

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
      if(depth > maxDepth) baseGen[A]
      else baseGen[A] ++ recursiveGen[A](genK) 

    Gen.oneOf(SortedMap(gens:_*).map(_._2)).flatMap(identity)
  }

  //All generators possible at depth 0 - the only public method
  def generators[A: Arbitrary: Cogen]: Gen[F[A]] = gen[A](0)
}

//Applicative is the first place that lets us introduce values in the context, if we discount InvariantMonoidal
trait ApplicativeGenerators[F[_]] extends Generators1[F] {
  implicit val F: Applicative[F]

  protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[F[A]])] = List("pure" -> genPure[A])

  protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[(String, Gen[F[A]])] =
    List(
      "map" -> genMap[A](deeper),
      "ap" -> genAp[A](deeper)
    )    

  private def genPure[A: Arbitrary]: Gen[F[A]] =
    arbitrary[A].map(_.pure[F])

  private def genMap[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] = for {
    fa <- deeper[A]
    f <- Arbitrary.arbitrary[A => A]
  } yield F.map(fa)(f)

  private def genAp[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] = for {
    fa <- deeper[A]
    ff <- deeper[A => A]
  } yield F.ap(ff)(fa)
}

trait MonadGenerators[F[_]] extends ApplicativeGenerators[F]{

  implicit val F: Monad[F]
  
  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[(String, Gen[F[A]])] = List(
    "flatMap" -> genFlatMap(deeper)
  ) ++ super.recursiveGen(deeper)

  private def genFlatMap[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] = {
    for {
      fa <- deeper[A]
      f <- Gen.function1[A, F[A]](deeper[A])
    } yield fa.flatMap(f)
  }
}

trait ApplicativeErrorGenerators[F[_], E] extends ApplicativeGenerators[F] {
  implicit val arbitraryE: Arbitrary[E]
  implicit val cogenE: Cogen[E]

  implicit val F: ApplicativeError[F, E]

  override protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[F[A]])] = List(
    "raiseError" -> genRaiseError[A]
  ) ++ super.baseGen[A]

  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[(String, Gen[F[A]])] = List(
    "handleErrorWith" -> genHandleErrorWith[A](deeper),
  ) ++ super.recursiveGen(deeper)

  private def genRaiseError[A]: Gen[F[A]] =
    arbitrary[E].map(F.raiseError[A](_))

  private def genHandleErrorWith[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] = {
    for {
      fa <- deeper[A]
      f <- Gen.function1[E, F[A]](deeper[A])
    } yield F.handleErrorWith(fa)(f)
  }
}

trait MonadErrorGenerators[F[_], E] extends ApplicativeErrorGenerators[F, E] with MonadGenerators[F] {
  implicit val F: MonadError[F, E]
}

trait SafeGenerators[F[_], E] extends MonadErrorGenerators[F, E] {
  implicit val F: Safe[F, E]
  type Case[A] = F.Case[A]
}

trait BracketGenerators[F[_], E] extends SafeGenerators[F, E] {
  implicit val F: Bracket[F, E]
  implicit def cogenCase[A: Cogen]: Cogen[Case[A]]
  
  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[(String, Gen[F[A]])] = List(
    "bracketCase" -> genBracketCase[A](deeper)
  ) ++ super.recursiveGen[A](deeper)

  import OutcomeGenerators._

  private def genBracketCase[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] = {
    for {
      acquire <- deeper[A]
      use <- Gen.function1[A, F[A]](deeper[A])
      release <- Gen.function2[A, Case[A], F[Unit]](deeper[Unit])
    } yield F.bracketCase(acquire)(use)(release)
  }
}

trait RegionGenerators[R[_[_], _], F[_], E] extends SafeGenerators[R[F, *], E] {
  implicit val F: Region[R, F, E]
  implicit def cogenCase[A: Cogen]: Cogen[Case[A]]

  def GenKF: GenK[F]
  type RF[A] = R[F, A]

  override protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[R[F,A]])] = List(
    "openCase" -> genOpenCase[A],
    "liftF" -> genLiftF[A]
  ) ++ super.baseGen[A]

  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[RF]): List[(String, Gen[R[F,A]])] = List(
    "supersededBy" -> genSupersededBy[A](deeper)
  ) ++ super.recursiveGen[A](deeper)

  private def genOpenCase[A: Arbitrary: Cogen]: Gen[RF[A]] =
    for {
      acquire <- GenKF[A]
      release <- Gen.function2[A, Case[Unit], F[Unit]](GenKF[Unit])
    } yield F.openCase(acquire)((a, c) => release(a, F.CaseInstance.void(c)))

  private def genLiftF[A: Arbitrary: Cogen]: Gen[RF[A]] =
    GenKF[A].map(F.liftF)

  private def genSupersededBy[A: Arbitrary: Cogen](deeper: GenK[RF]): Gen[RF[A]] =
    for {
      rfa <- deeper[Unit]
      rfb <- deeper[A]
    } yield F.supersededBy(rfa, rfb)
}

trait ConcurrentGenerators[F[_], E] extends MonadErrorGenerators[F, E] {
  implicit val F: Concurrent[F, E]

  override protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[F[A]])] = List(
    "canceled" -> genCanceled[A],
    "cede" -> genCede[A],
    "never" -> genNever[A]
  ) ++ super.baseGen[A]
  
  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[(String, Gen[F[A]])] = List(
    "uncancelable" -> genUncancelable[A](deeper),
    "racePair" -> genRacePair[A](deeper),
    "start" -> genStart[A](deeper),
    "join" -> genJoin[A](deeper),
  ) ++ super.recursiveGen(deeper)

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

  private def genRacePair[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    for {
      fa <- deeper[A]
      fb <- deeper[A]

      cancel <- arbitrary[Boolean]

      back = F.racePair(fa, fb) flatMap {
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

trait ConcurrentRegionGenerators[R[_[_], _], F[_], E] extends RegionGenerators[R, F, E] with ConcurrentGenerators[R[F, *], E]  {
  implicit val F: ConcurrentRegion[R, F, E]
  override type Case[A] = F.Case[A]
}

object OutcomeGenerators {
  def outcomeGenerators[F[_]: Applicative, E: Arbitrary: Cogen] = new ApplicativeErrorGenerators[Outcome[F, E, *], E] {
    val arbitraryE: Arbitrary[E] = implicitly
    val cogenE: Cogen[E] = implicitly
    implicit val F: ApplicativeError[Outcome[F, E, *], E] = Outcome.applicativeError[F, E]

    override protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[Outcome[F,E,A]])] = List(
      "const(Canceled)" -> Gen.const(Outcome.Canceled)
    ) ++ super.baseGen[A]
  }
  
  implicit def arbitraryOutcome[F[_]: Applicative, E: Arbitrary: Cogen, A: Arbitrary: Cogen]: Arbitrary[Outcome[F, E, A]] =
    Arbitrary {
      outcomeGenerators[F, E].generators[A]
    }

  implicit def cogenOutcome[F[_], E: Cogen, A](implicit A: Cogen[F[A]]): Cogen[Outcome[F, E, A]] = Cogen[Option[Either[E, F[A]]]].contramap {
    case Outcome.Canceled => None
    case Outcome.Completed(fa) => Some(Right(fa))
    case Outcome.Errored(e)    => Some(Left(e))
  }

  implicit def cogenKOutcome[F[_], E: Cogen](implicit cogenKF: CogenK[F]): CogenK[Outcome[F, E, *]] = new CogenK[Outcome[F, E, *]] {
    def cogen[A: Cogen]: Cogen[Outcome[F,E,A]] = cogenOutcome[F, E, A](Cogen[E], cogenKF.cogen[A])
  }
}
