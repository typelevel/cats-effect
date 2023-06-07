package cats.effect
package std

import cats.Eq
import cats.laws.discipline.{FunctorTests, arbitrary}
import cats.syntax.all._
import org.scalacheck.{Arbitrary, Gen}
import org.typelevel.discipline.specs2.mutable.Discipline
import cats.effect.laws.SyncTests
import cats.effect.syntax.all._
import cats.laws.discipline.arbitrary._
import cats.laws.discipline.eq._

class QueueLawTests extends BaseSpec with Discipline {

//  implicit def eqQueueSource[F[_], A: Eq]: Eq[Queue[F, A]] = Eq.fromUniversalEquals

  def toList[A](q: Queue[IO, A]): IO[List[A]] =
    for {
      size <- q.size
      list <- q.tryTakeN(size.some)
    } yield list

  implicit def eqIOA[A: Eq](implicit runtime: unsafe.IORuntime): Eq[IO[List[A]]] =
    Eq.by(_.unsafeRunSync())

  implicit def eqForQueue[A: Eq](implicit runtime: unsafe.IORuntime): Eq[Queue[IO, A]] =
    Eq.by(toList)

  implicit def arbQueue[A: Arbitrary](
      implicit runtime: unsafe.IORuntime): Arbitrary[QueueSource[IO, A]] = Arbitrary(genQueue)

  def fromList[A: Arbitrary](as: List[A]): IO[Queue[IO, A]] = {
    for {
      queue <- Queue.bounded[IO, A](Int.MaxValue)
      _ <- as.traverse(a => queue.offer(a))
    } yield queue
  }

  def genQueue[A: Arbitrary](implicit runtime: unsafe.IORuntime): Gen[Queue[IO, A]] =
    for {
      list <- Arbitrary.arbitrary[List[A]]
      queue = fromList(list).unsafeRunSync()
    } yield queue

  checkAll("QueueFunctorLaws", FunctorTests[QueueSource[IO, *]].functor)
}

// private def buildQueueGen[A: Arbitrary]: IO[Gen[Queue[IO, A]]] =
//   for {
//     bounded <- Queue.bounded[IO, A](Int.MaxValue)
//     unbounded <- Queue.unbounded[IO, A]
//     dropping <- Queue.dropping[IO, A](Int.MaxValue)
//     circular <- Queue.circularBuffer[IO, A](Int.MaxValue)
//     gen = Gen.oneOf(bounded, unbounded, dropping, circular)
//   } yield gen
