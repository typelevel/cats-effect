/*
 * Copyright 2020 Daniel Spiewak
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

import cats.implicits._

import org.scalacheck.{Arbitrary, Cogen, Gen}, Arbitrary.arbitrary
import cats.~>
import cats.Monad
import cats.Applicative
import cats.Functor
import cats.MonadError
import cats.Traverse

trait GenK[F[_]] {
  def apply[A: Arbitrary: Cogen]: Gen[F[A]]
}

// Generators for * -> * kinded types
trait Generators1[F[_]] {
  protected val maxDepth: Int = 10
  
  //todo: uniqueness based on... names, I guess. Have to solve the diamond problem somehow

  //Generators of base cases, with no recursion
  protected def baseGen[A: Arbitrary: Cogen]: List[Gen[F[A]]]

  //Only recursive generators - the argument is a generator of the next level of depth
  protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[Gen[F[A]]]

  //All generators possible at depth [[depth]]
  private def gen[A: Arbitrary: Cogen](depth: Int): Gen[F[A]] = {
    val genK: GenK[F] = new GenK[F] {
      def apply[B: Arbitrary: Cogen]: Gen[F[B]] = Gen.delay(gen(depth + 1))
    }
    
    val gens =
      if(depth > maxDepth) baseGen[A]
      else baseGen[A] ++ recursiveGen[A](genK)

    Gen.oneOf(gens).flatMap(identity)
  }

  //All generators possible at depth 0 - the only public method
  def generators[A: Arbitrary: Cogen]: Gen[F[A]] = gen[A](0)
}

//Applicative is the first place that lets us introduce values in the context, if we discount InvariantMonoidal
trait ApplicativeGenerators[F[_]] extends Generators1[F] {
  implicit val F: Applicative[F]

  protected def baseGen[A: Arbitrary: Cogen]: List[Gen[F[A]]] = List(genPure[A])

  protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[Gen[F[A]]] = 
    List(
      genMap[A](deeper),
      genAp[A](deeper)
    )    

  def genPure[A: Arbitrary]: Gen[F[A]] =
    arbitrary[A].map(_.pure[F])

  def genMap[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] = for {
    fa <- deeper[A]
    f <- Arbitrary.arbitrary[A => A]
  } yield F.map(fa)(f)

  def genAp[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] = for {
    fa <- deeper[A]
    ff <- deeper[A => A]
  } yield F.ap(ff)(fa)
}

trait MonadGenerators[F[_]] extends ApplicativeGenerators[F]{

  implicit val F: Monad[F]
  
  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[Gen[F[A]]] = List(
    genFlatMap(deeper)
  ) ++ super.recursiveGen(deeper)

  def genFlatMap[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] = {
    for {
      fa <- deeper[A]
      f <- Gen.function1[A, F[A]](deeper[A])
    } yield fa.flatMap(f)
  }
}

trait MonadErrorGenerators[F[_], E] extends MonadGenerators[F] {
  implicit val arbitraryE: Arbitrary[E]
  implicit val cogenE: Cogen[E]

  implicit val F: MonadError[F, E]

  override protected def baseGen[A: Arbitrary: Cogen]: List[Gen[F[A]]] = List(
    genRaiseError[A]
  ) ++ super.baseGen[A]

  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[Gen[F[A]]] = List(
    genHandleErrorWith[A](deeper),
  ) ++ super.recursiveGen(deeper)


  def genRaiseError[A]: Gen[F[A]] =
    arbitrary[E].map(F.raiseError[A](_))

  def genHandleErrorWith[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] = {
    for {
      fa <- deeper[A]
      f <- Gen.function1[E, F[A]](deeper[A])
    } yield F.handleErrorWith(fa)(f)
  }
}

trait BracketGenerators[F[_], E] extends MonadErrorGenerators[F, E] {
  implicit val F: Bracket[F, E]
  type Case[A] = F.Case[A]
  implicit def cogenCase[A: Cogen]: Cogen[Case[A]]
  
  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[Gen[F[A]]] = List(
    genBracketCase[A](deeper)
  ) ++ super.recursiveGen[A](deeper)

  import OutcomeGenerators._

  def genBracketCase[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] = {
    for {
      acquire <- deeper[A]
      use <- Gen.function1[A, F[A]](deeper[A])
      release <- Gen.function2[A, Case[A], F[Unit]](deeper[Unit])
    } yield F.bracketCase(acquire)(use)(release)
  }
}

trait ConcurrentGenerators[F[_], E] extends MonadErrorGenerators[F, E] {
  implicit val F: Concurrent[F, E]

  override protected def baseGen[A: Arbitrary: Cogen]: List[Gen[F[A]]] = List(
    genCanceled[A],
    genCede[A],
    genNever[A]
  ) ++ super.baseGen[A]
  
  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[Gen[F[A]]] = List(
    genUncancelable[A](deeper),
    genRacePair[A](deeper),
    genStart[A](deeper),
    genJoin[A](deeper),
  ) ++ super.recursiveGen(deeper)

  def genCanceled[A: Arbitrary]: Gen[F[A]] =
    arbitrary[A].map(F.canceled.as(_))

  def genCede[A: Arbitrary]: Gen[F[A]] =
    arbitrary[A].map(F.cede.as(_))

  def genNever[A]: Gen[F[A]] =
    F.never[A]

  // TODO we can't really use poll :-( since we can't Cogen FunctionK
  def genUncancelable[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    deeper[A].map(pc => F.uncancelable(_ => pc))

  def genStart[A: Arbitrary](deeper: GenK[F]): Gen[F[A]] =
    deeper[Unit].flatMap(pc => arbitrary[A].map(a => F.start(pc).as(a)))

  def genJoin[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    for {
      fiber <- deeper[A]
      cont <- deeper[Unit]
      a <- arbitrary[A]
    } yield F.start(fiber).flatMap(f => cont >> f.join).as(a)

  def genRacePair[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
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

object OutcomeGenerators {
  def outcomeGenerators[F[_]: Traverse: Monad, E: Arbitrary: Cogen] = new MonadErrorGenerators[Outcome[F, E, *], E] {
    val arbitraryE: Arbitrary[E] = implicitly
    val cogenE: Cogen[E] = implicitly
    implicit val F: MonadError[Outcome[F, E, *], E] = Outcome.monadError[F, E]

    override protected def baseGen[A: Arbitrary: Cogen]: List[Gen[Outcome[F,E,A]]] = List(
      Gen.const(Outcome.Canceled)
    ) ++ super.baseGen[A]
  }
  
  implicit def arbitraryOutcome[F[_]: Traverse: Monad, E: Arbitrary: Cogen, A: Arbitrary: Cogen]: Arbitrary[Outcome[F, E, A]] =
    Arbitrary {
      outcomeGenerators[F, E].generators[A]
    }

  implicit def cogenOutcome[F[_], E: Cogen, A](implicit A: Cogen[F[A]]): Cogen[Outcome[F, E, A]] = Cogen[Option[Either[E, F[A]]]].contramap {
    case Outcome.Canceled => None
    case Outcome.Completed(fa) => Some(Right(fa))
    case Outcome.Errored(e) => Some(Left(e))
  }
}
