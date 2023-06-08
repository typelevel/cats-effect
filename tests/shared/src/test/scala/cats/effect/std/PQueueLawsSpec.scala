package cats.effect
package std

import cats.{Eq, Order}
import cats.effect._
import cats.effect.kernel.Outcome
import cats.laws.discipline.{ContravariantTests, FunctorTests, InvariantTests}
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

  def fromList[A: Arbitrary](as: List[A])(implicit ticker: Ticker): IO[PQueue[IO, A]] = {
    for {
      queue <- PQueue.bounded[IO, A](Int.MaxValue)
      _ <- as.traverse(a => queue.offer(a))
    } yield queue
  }

  def genQueue[A: Arbitrary](implicit ticker: Ticker): Gen[PQueue[IO, A]] =
    for {
      list <- Arbitrary.arbitrary[List[A]]
      queue = fromList(list)
      // This code should be improved
    } yield (unsafeRun(queue) match {
      case Outcome.Succeeded(a) => a
      case _ => None
    }).get

  def toListSource[A](q: PQueueSource[IO, A]): IO[List[A]] =
    for {
      size <- q.size
      list <- q.tryTakeN(size.some)
    } yield list

  implicit def eqForPQueueSource[A: Eq](implicit ticker: Ticker): Eq[PQueueSource[IO, A]] =
    Eq.by(toListSource)

  implicit def arbPQueueSource[A: Arbitrary](
      implicit ticker: Ticker): Arbitrary[PQueueSource[IO, A]] =
    Arbitrary(genQueue)

  {
    implicit val ticker = Ticker()
    checkAll("PQueueFunctorLaws", FunctorTests[PQueueSource[IO, *]].functor[Int, Int, String])
  }
}
