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

package cats.effect.kernel
package testkit

import cats.{Applicative, ApplicativeError, Eq, Monad, MonadError}
import cats.syntax.all._

import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalacheck.Arbitrary.arbitrary

import scala.collection.immutable.SortedMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

trait GenK[F[_]] extends Serializable {
  def apply[A: Arbitrary: Cogen]: Gen[F[A]]
}

// Generators for * -> * kinded types
trait Generators1[F[_]] extends Serializable {
  protected val maxDepth: Int = 10

  // todo: uniqueness based on... names, I guess. Have to solve the diamond problem somehow

  // Generators of base cases, with no recursion
  protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[F[A]])] = {
    // prevent unused implicit param warnings, the params need to stay because
    // this method is overridden in subtraits
    val _ = (implicitly[Arbitrary[A]], implicitly[Cogen[A]])
    Nil
  }

  // Only recursive generators - the argument is a generator of the next level of depth
  protected def recursiveGen[A: Arbitrary: Cogen](
      deeper: GenK[F]): List[(String, Gen[F[A]])] = {
    // prevent unused params warnings, the params need to stay because
    // this method is overridden in subtraits
    val _ = (deeper, implicitly[Arbitrary[A]], implicitly[Cogen[A]])
    Nil
  }

  // All generators possible at depth [[depth]]
  private def gen[A: Arbitrary: Cogen](depth: Int): Gen[F[A]] = {
    val genK: GenK[F] = new GenK[F] {
      def apply[B: Arbitrary: Cogen]: Gen[F[B]] = Gen.delay(gen(depth + 1))
    }

    val gens =
      if (depth > maxDepth) baseGen[A]
      else baseGen[A] ++ recursiveGen[A](genK)

    Gen.oneOf(SortedMap(gens: _*).map(_._2)).flatMap(identity)
  }

  // All generators possible at depth 0 - the only public method
  def generators[A: Arbitrary: Cogen]: Gen[F[A]] = gen[A](0)
}

//Applicative is the first place that lets us introduce values in the context, if we discount InvariantMonoidal
trait ApplicativeGenerators[F[_]] extends Generators1[F] {
  implicit val F: Applicative[F]

  override protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[F[A]])] =
    List("pure" -> genPure[A]) ++ super.baseGen[A]

  override protected def recursiveGen[A: Arbitrary: Cogen](
      deeper: GenK[F]): List[(String, Gen[F[A]])] =
    List(
      "map" -> genMap[A](deeper),
      "ap" -> genAp[A](deeper)
    ) ++ super.recursiveGen(deeper)

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

trait ClockGenerators[F[_]] extends Generators1[F] {
  implicit val F: Clock[F]

  implicit protected val arbitraryFD: Arbitrary[FiniteDuration]

  override protected def baseGen[A: Arbitrary: Cogen] =
    List("monotonic" -> genMonotonic[A], "realTime" -> genRealTime[A]) ++ super.baseGen[A]

  private def genMonotonic[A: Arbitrary] =
    arbitrary[A].map(F.applicative.as(F.monotonic, _))

  private def genRealTime[A: Arbitrary] =
    arbitrary[A].map(F.applicative.as(F.realTime, _))
}

trait SyncGenerators[F[_]] extends MonadErrorGenerators[F, Throwable] with ClockGenerators[F] {
  implicit val F: Sync[F]

  override protected def baseGen[A: Arbitrary: Cogen] =
    ("delay" -> arbitrary[A].map(F.delay(_))) :: super.baseGen[A]
}

trait MonadCancelGenerators[F[_], E] extends MonadErrorGenerators[F, E] {
  implicit val F: MonadCancel[F, E]

  override protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[F[A]])] =
    ("canceled" -> genCanceled[A]) :: super.baseGen[A]

  override protected def recursiveGen[A](
      deeper: GenK[F])(implicit AA: Arbitrary[A], AC: Cogen[A]): List[(String, Gen[F[A]])] =
    List(
      "forceR" -> genForceR[A](deeper),
      "uncancelable" -> genUncancelable[A](deeper),
      "onCancel" -> genOnCancel[A](deeper)
    ) ++ super.recursiveGen(deeper)(AA, AC)

  private def genCanceled[A: Arbitrary]: Gen[F[A]] =
    arbitrary[A].map(F.canceled.as(_))

  private def genForceR[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    for {
      left <- deeper[Unit]
      right <- deeper[A]
    } yield F.forceR(left)(right)

  // TODO we can't really use poll :-( since we can't Cogen FunctionK
  private def genUncancelable[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    deeper[A].map(pc =>
      F.uncancelable(_ => pc)
        .flatMap(F.pure(_))
        .handleErrorWith(
          F.raiseError(_)
        )) // this is a bit of a hack to get around functor law breakage

  private def genOnCancel[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    for {
      fa <- deeper[A]
      fin <- deeper[Unit]
    } yield F.onCancel(fa, fin)
}

trait GenSpawnGenerators[F[_], E] extends MonadCancelGenerators[F, E] {
  implicit val F: GenSpawn[F, E]

  override protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[F[A]])] =
    List(
      "cede" -> genCede[A],
      "never" -> genNever[A]
    ) ++ super.baseGen[A]

  override protected def recursiveGen[A](
      deeper: GenK[F])(implicit AA: Arbitrary[A], AC: Cogen[A]): List[(String, Gen[F[A]])] =
    List(
      "racePair" -> genRacePair[A](deeper),
      "start" -> genStart[A](deeper),
      "join" -> genJoin[A](deeper)) ++ super.recursiveGen(deeper)(AA, AC)

  private def genCede[A: Arbitrary]: Gen[F[A]] =
    arbitrary[A].map(F.cede.as(_))

  private def genNever[A]: Gen[F[A]] =
    F.never[A]

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

      back = F.racePair(fa, fb).flatMap {
        case Left((oc, f)) =>
          if (cancel)
            f.cancel *> oc.embedNever
          else
            f.join *> oc.embedNever

        case Right((f, oc)) =>
          if (cancel)
            f.cancel *> oc.embedNever
          else
            f.join *> oc.embedNever
      }
    } yield back
}

trait GenTemporalGenerators[F[_], E] extends GenSpawnGenerators[F, E] with ClockGenerators[F] {
  implicit val F: GenTemporal[F, E]

  override protected def baseGen[A: Arbitrary: Cogen] =
    ("sleep" -> genSleep[A]) :: super.baseGen[A]

  private def genSleep[A: Arbitrary] =
    for {
      t <- arbitraryFD.arbitrary
      a <- arbitrary[A]
    } yield F.sleep(t).as(a)
}

trait AsyncGenerators[F[_]] extends GenTemporalGenerators[F, Throwable] with SyncGenerators[F] {
  implicit val F: Async[F]

  implicit protected val arbitraryEC: Arbitrary[ExecutionContext]
  implicit protected val cogenFU: Cogen[F[Unit]]

  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]) =
    List("async" -> genAsync[A](deeper), "evalOn" -> genEvalOn[A](deeper)) ++ super
      .recursiveGen[A](deeper)

  private def genAsync[A: Arbitrary](deeper: GenK[F]) =
    for {
      result <- arbitrary[Either[Throwable, A]]

      fo <- deeper[Option[F[Unit]]](
        Arbitrary(Gen.option[F[Unit]](deeper[Unit])),
        Cogen.cogenOption(cogenFU))
    } yield F
      .async[A](k => F.delay(k(result)) >> fo)
      .flatMap(F.pure(_))
      .handleErrorWith(F.raiseError(_))

  private def genEvalOn[A: Arbitrary: Cogen](deeper: GenK[F]) =
    for {
      fa <- deeper[A]
      ec <- arbitraryEC.arbitrary
    } yield F.evalOn(fa, ec)
}

trait AsyncGeneratorsWithoutEvalShift[F[_]]
    extends GenTemporalGenerators[F, Throwable]
    with SyncGenerators[F] {
  implicit val F: Async[F]
  implicit protected val cogenFU: Cogen[F[Unit]] = Cogen[Unit].contramap(_ => ())

  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]) =
    ("async" -> genAsync[A](deeper)) :: super.recursiveGen[A](deeper)

  private def genAsync[A: Arbitrary](deeper: GenK[F]) =
    for {
      result <- arbitrary[Either[Throwable, A]]

      fo <- deeper[Option[F[Unit]]](
        Arbitrary(Gen.option[F[Unit]](deeper[Unit])),
        Cogen.cogenOption(cogenFU))
    } yield F
      .async[A](k => F.delay(k(result)) >> fo)
      .flatMap(F.pure(_))
      .handleErrorWith(F.raiseError(_))
}

trait ParallelFGenerators {
  implicit def arbitraryParallelF[F[_], A](
      implicit ArbF: Arbitrary[F[A]]): Arbitrary[ParallelF[F, A]] =
    Arbitrary {
      ArbF.arbitrary.map(f => ParallelF(f))
    }

  implicit def eqParallelF[F[_], A](implicit EqF: Eq[F[A]]): Eq[ParallelF[F, A]] =
    EqF.imap(ParallelF.apply)(ParallelF.value)
}
object ParallelFGenerators extends ParallelFGenerators

trait OutcomeGenerators {
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
      case Outcome.Succeeded(fa) => Some(Right(fa))
      case Outcome.Errored(e) => Some(Left(e))
    }
}
object OutcomeGenerators extends OutcomeGenerators

trait SyncTypeGenerators {

  implicit val arbitrarySyncType: Arbitrary[Sync.Type] = {
    import Sync.Type._

    Arbitrary(Gen.oneOf(Delay, Blocking, InterruptibleOnce, InterruptibleMany))
  }

  implicit val cogenSyncType: Cogen[Sync.Type] = {
    import Sync.Type._

    Cogen[Int] contramap {
      case Delay => 0
      case Blocking => 1
      case InterruptibleOnce => 2
      case InterruptibleMany => 3
    }
  }
}
object SyncTypeGenerators extends SyncTypeGenerators
