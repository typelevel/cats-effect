package cats.effect
package std

import cats.Eq
import cats.effect._
import cats.effect.kernel.Outcome
import cats.laws.discipline.{FunctorTests, InvariantTests}
import cats.syntax.all._

import org.scalacheck.{Arbitrary, Gen}
import org.typelevel.discipline.specs2.mutable.Discipline

class DequeueLawsSpec extends BaseSpec with Discipline {

  sequential

  def toList[A](q: Dequeue[IO, A]): IO[List[A]] =
    for {
      size <- q.size
      list <- q.tryTakeN(size.some)
    } yield list

  def fromList[A: Arbitrary](as: List[A]): IO[Dequeue[IO, A]] = {
    for {
      queue <- Dequeue.bounded[IO, A](Int.MaxValue)
      _ <- as.traverse(a => queue.offer(a))
    } yield queue
  }

  def genDequeue[A: Arbitrary](implicit ticker: Ticker): Gen[Dequeue[IO, A]] =
    for {
      list <- Arbitrary.arbitrary[List[A]]
      queue = fromList(list)
      outcome = unsafeRun(queue) match {
        case Outcome.Succeeded(a) => a
        case _ => None
      }
    } yield outcome.get

  def toListSource[A](q: DequeueSource[IO, A]): IO[List[A]] =
    for {
      size <- q.size
      list <- q.tryTakeN(size.some)
    } yield list

  implicit def eqForDequeueSource[A: Eq](implicit ticker: Ticker): Eq[DequeueSource[IO, A]] =
    Eq.by(toListSource)

  implicit def arbDequeueSource[A: Arbitrary](
      implicit ticker: Ticker): Arbitrary[DequeueSource[IO, A]] =
    Arbitrary(genDequeue)

  {
    implicit val ticker = Ticker()
    checkAll("DequeueFunctorLaws", FunctorTests[DequeueSource[IO, *]].functor[Int, Int, String])
  }

  implicit def eqForDequeue[A: Eq](implicit ticker: Ticker): Eq[Dequeue[IO, A]] =
    Eq.by(toList)

  implicit def arbDequeue[A: Arbitrary](implicit ticker: Ticker): Arbitrary[Dequeue[IO, A]] =
    Arbitrary(genDequeue)

  {
    implicit val ticker = Ticker()
    checkAll("DequeueInvariantLaws", InvariantTests[Dequeue[IO, *]].invariant[Int, Int, String])
  }
}
