package cats
package effect
package kernel

import cats.data.OptionT
import cats.effect.kernel.testkit.PureConcGenerators._
import cats.effect.kernel.testkit.pure._
import cats.laws.discipline.arbitrary.catsLawsArbitraryForOptionT
import cats.mtl.laws.discipline.{LiftKindTests, LiftValueTests}
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.scalacheck.{Arbitrary, Gen}

import munit.DisciplineSuite

class ResourceSuite extends DisciplineSuite {
  type PCT[A] = PureConc[Throwable, A]

  private[this] val counter: PCT[Ref[PCT, Long]] = Concurrent[PCT].ref(0)

  implicit val eqThrowable: Eq[Throwable] = Eq.fromUniversalEquals

  implicit val arbitraryScope: Arbitrary[PCT ~> PCT] =
    Arbitrary {
      Gen.const {
        new (PCT ~> PCT) {
          def apply[A](fa: PCT[A]): PCT[A] =
            for {
              ref <- counter
              res <- ref.update(_ + 1) >> fa
            } yield res
        }
      }
    }

  implicit def arbitraryPCTUnit(): Arbitrary[PCT[Unit]] =
    Arbitrary {
      Gen.const {
        counter.flatMap(_.update(_ * 10)).void
      }
    }

  implicit def eqResource[F[_], A](
      implicit F: MonadCancelThrow[F],
      eqFAUnit: Eq[F[(A, Unit)]]
  ): Eq[Resource[F, A]] =
    Eq.by {
      _.allocated.flatMap {
        case (acquire, release) =>
          release.map(acquire -> _)
      }
    }

  implicit def arbitraryResource[F[_]: Functor, A](
      implicit arbFA: Arbitrary[F[A]],
      arbFUnit: Arbitrary[F[Unit]]
  ): Arbitrary[Resource[F, A]] =
    Arbitrary {
      for {
        acquire <- arbFA.arbitrary
        release <- arbFUnit.arbitrary
      } yield Resource(acquire.map(_ -> release))
    }

  checkAll(
    "LiftValue[PureConc, Resource[PureConc, *]]",
    LiftValueTests[PCT, Resource[PCT, *]].liftValue[Int, Int]
  )
  checkAll(
    "LiftValue[PureConc, Resource[OptionT[PureConc, *], *]]",
    LiftValueTests[PCT, Resource[OptionT[PCT, *], *]].liftValue[Int, Int]
  )
  checkAll(
    "LiftKind[PureConc, Resource[PureConc, *]]",
    LiftKindTests[PCT, Resource[PCT, *]].liftKind[Int, Int]
  )
  checkAll(
    "LiftKind[PureConc, Resource[OptionT[PureConc, *], *]]",
    LiftKindTests[PCT, Resource[OptionT[PCT, *], *]].liftKind[Int, Int]
  )
}
