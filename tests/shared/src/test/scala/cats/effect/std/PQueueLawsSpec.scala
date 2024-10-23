package cats.effect
package std

import cats.{Eq, Order}
import cats.effect._
import cats.effect.kernel.Outcome
import cats.laws.discipline.{FunctorTests, InvariantTests}
import cats.syntax.all._

import org.scalacheck.{Arbitrary, Gen}
import org.typelevel.discipline.specs2.mutable.Discipline

class PQueueLawsSpec extends BaseSpec with Discipline {

  sequential

  def toList[A](q: PQueue[IO, A]): IO[List[A]] =
    for {
      size <- q.size
      list <- q.tryTakeN(size.some)
    } yield list

  def fromList[A: Arbitrary](as: List[A])(implicit ord: Order[A]): IO[PQueue[IO, A]] = {
    for {
      queue <- PQueue.bounded[IO, A](Int.MaxValue)
      _ <- as.traverse(a => queue.offer(a))
    } yield queue
  }

  def genPQueue[A: Arbitrary](implicit ticker: Ticker, ord: Order[A]): Gen[PQueue[IO, A]] =
    for {
      list <- Arbitrary.arbitrary[List[A]]
      queue = fromList(list)
      outcome = unsafeRun(queue) match {
        case Outcome.Succeeded(a) => a
        case _ => None
      }
    } yield outcome.get

  def toListSource[A](q: PQueueSource[IO, A]): IO[List[A]] =
    for {
      size <- q.size
      list <- q.tryTakeN(size.some)
    } yield list

  implicit def eqForPQueueSource[A: Eq](implicit ticker: Ticker): Eq[PQueueSource[IO, A]] =
    Eq.by(toListSource)

  implicit def arbPQueueSource[A: Arbitrary](
      implicit ticker: Ticker,
      ord: Order[A]): Arbitrary[PQueueSource[IO, A]] =
    Arbitrary(genPQueue)

  {
    implicit val ticker = Ticker()
    checkAll("PQueueFunctorLaws", FunctorTests[PQueueSource[IO, *]].functor[Int, Int, String])
  }

  implicit def eqForPQueue[A: Eq](implicit ticker: Ticker): Eq[PQueue[IO, A]] =
    Eq.by(toList)

  implicit def arbPQueue[A: Arbitrary](
      implicit ticker: Ticker,
      ord: Order[A]): Arbitrary[PQueue[IO, A]] =
    Arbitrary(genPQueue)

  {
    implicit val ticker = Ticker()
    checkAll("PQueueInvariantLaws", InvariantTests[PQueue[IO, *]].invariant[Int, Int, String])
  }
}
