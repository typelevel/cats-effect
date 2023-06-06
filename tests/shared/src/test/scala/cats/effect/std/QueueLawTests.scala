package cats.effect
package std

import cats.Eq
import cats.laws.discipline.{FunctorTests, arbitrary}
import cats.syntax.all._
import org.scalacheck.{Arbitrary, Gen}
import org.typelevel.discipline.specs2.mutable.Discipline

class QueueLawTests extends BaseSpec with Discipline {

//  implicit def eqQueueSource[F[_], A: Eq]: Eq[Queue[F, A]] = Eq.fromUniversalEquals

  def toList[A](q: Queue[IO, A]): IO[List[A]] =
    for {
      size <- q.size
      list <- q.tryTakeN(size.some)
    } yield list

  implicit def eqIOA[A: Eq](implicit ticker: Ticker): Eq[IO[List[A]]] =
    Eq.by(_.unsafeRunSync())

  implicit def eqForQueue[A: Eq]: Eq[Queue[IO, A]] = Eq.by(q => toList(q))

  implicit def arbQueue[A: Arbitrary]: Arbitrary[Queue[IO, A]] = Arbitrary(genQueue)

  def fromList[A: Arbitrary](as: List[A]): IO[Queue[IO, A]] = {
    for {
      queue <- Queue.bounded[IO, A](Int.MaxValue)
      _ <- as.traverse(a => queue.offer(a))
    } yield queue
  }

  def genQueue[A: Arbitrary]: Gen[Queue[IO, A]] = {
    for {
      list <- Arbitrary[List[A]].map(_.arbitrary)
      queueGen = list.map(l => fromList(l).unsafeRunSync())
    } yield queueGen
  }

  // private def buildQueueGen[A: Arbitrary]: IO[Gen[Queue[IO, A]]] =
  //   for {
  //     bounded <- Queue.bounded[IO, A](Int.MaxValue)
  //     unbounded <- Queue.unbounded[IO, A]
  //     dropping <- Queue.dropping[IO, A](Int.MaxValue)
  //     circular <- Queue.circularBuffer[IO, A](Int.MaxValue)
  //     gen = Gen.oneOf(bounded, unbounded, dropping, circular)
  //   } yield gen

  checkAll("Tree.FunctorLaws", FunctorTests[Queue[IO, *]].functor[Int, Int, String])
}
