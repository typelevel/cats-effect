package cats.effect.std

import cats.syntax.all._
import cats.laws.discipline.FunctorTests
import org.specs2.mutable.Specification
import QueueSource.catsFunctorForQueueSource
import cats.Eq
import cats.effect.IO
import cats.effect.kernel.testkit.freeEval.FreeEitherSync
import org.scalacheck.{Arbitrary, Gen}
import org.typelevel.discipline.specs2.mutable.Discipline
class QueueLawTests extends Specification with Discipline {

  implicit def eqQueueSource[F[_], A: Eq]: Eq[QueueSource[F, A]] = Eq.fromUniversalEquals

  implicit def arbQueue[A: Arbitrary]: Arbitrary[Queue[IO, A]] =
    (for {
      x <- buildQueueGen
      arb = Arbitrary(x)
    } yield arb).unsafeRunSync()

  private def buildQueueGen[A: Arbitrary]: IO[Gen[Queue[IO, A]]] =
    for {
      bounded <- Queue.bounded[IO, A](Int.MaxValue)
      unbounded <- Queue.unbounded[IO, A]
      dropping <- Queue.dropping[IO, A](Int.MaxValue)
      circular <- Queue.circularBuffer[IO, A](Int.MaxValue)
      gen = Gen.oneOf(bounded, unbounded, dropping, circular)
    } yield gen

  checkAll(
    "Tree.FunctorLaws",
    FunctorTests[QueueSource[FreeEitherSync, *]].functor[Int, Int, String])
}
