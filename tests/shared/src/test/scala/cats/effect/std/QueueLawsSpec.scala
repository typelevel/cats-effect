package cats.effect
package std

import cats.Eq
import cats.effect._
import cats.effect.kernel.Outcome
import cats.laws.discipline.{FunctorTests, InvariantTests, ContravariantTests}
import cats.syntax.all._

import org.scalacheck.{Arbitrary, Gen}
import org.typelevel.discipline.specs2.mutable.Discipline

class QueueLawsSpec extends BaseSpec with Discipline {

  sequential

  def toList[A](q: Queue[IO, A]): IO[List[A]] =
    for {
      size <- q.size
      list <- q.tryTakeN(size.some)
    } yield list

  def fromList[A: Arbitrary](as: List[A]): IO[Queue[IO, A]] = {
    for {
      queue <- Queue.bounded[IO, A](Int.MaxValue)
      _ <- as.traverse(a => queue.offer(a))
    } yield queue
  }

  def genQueue[A: Arbitrary](implicit ticker: Ticker): Gen[Queue[IO, A]] =
    for {
      list <- Arbitrary.arbitrary[List[A]]
      queue = fromList(list)
      // This code should be improved
    } yield (unsafeRun(queue) match {
      case Outcome.Succeeded(a) => a
      case _ => None
    }).get

  def toListSource[A](q: QueueSource[IO, A]): IO[List[A]] =
    for {
      size <- q.size
      list <- q.tryTakeN(size.some)
    } yield list

  implicit def eqForQueueSource[A: Eq](implicit ticker: Ticker): Eq[QueueSource[IO, A]] =
    Eq.by(toListSource)

  implicit def arbQueueSource[A: Arbitrary](
      implicit ticker: Ticker): Arbitrary[QueueSource[IO, A]] =
    Arbitrary(genQueue)

  {
    implicit val ticker = Ticker()
    checkAll("QueueFunctorLaws", FunctorTests[QueueSource[IO, *]].functor[Int, Int, String])
  }

  implicit def eqForQueue[A: Eq](implicit ticker: Ticker): Eq[Queue[IO, A]] =
    Eq.by(toList)

  implicit def arbQueue[A: Arbitrary](implicit ticker: Ticker): Arbitrary[Queue[IO, A]] =
    Arbitrary(genQueue)

  {
    implicit val ticker = Ticker()
    checkAll("QueueInvariantLaws", InvariantTests[Queue[IO, *]].invariant[Int, Int, String])
  }

  implicit def eqForQueueSink[A: Eq](implicit ticker: Ticker): Eq[QueueSink[IO, A]] =
    Eq.by(toList)

  implicit def arbQueueSink[A: Arbitrary](
      implicit ticker: Ticker): Arbitrary[QueueSink[IO, A]] =
    Arbitrary(genQueue)

  {
    implicit val ticker = Ticker()
    checkAll(
      "QueueContravariantLaws",
      ContravariantTests[QueueSink[IO, *]].contravariant[Int, Int, String])

  }
}
