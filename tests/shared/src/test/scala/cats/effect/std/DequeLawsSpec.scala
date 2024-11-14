package cats.effect

package std

import cats.Eq
import cats.effect.*
import cats.effect.kernel.Outcome
import cats.effect.testkit.TestInstances
import cats.laws.discipline.{FunctorTests, InvariantTests}
import cats.syntax.all.*
import org.scalacheck.{Arbitrary, Gen}
import org.typelevel.discipline.specs2.mutable.Discipline
import specs2.arguments.sequential

class DequeueLawsSpec extends BaseSpec with Discipline with TestInstances {
  sequential

  {
    implicit val ticker = Ticker()
    implicit def eqByDequeSource[A: Eq]: Eq[Dequeue[IO, A]] = getDequeueEq[A, Dequeue[IO, A]]

    checkAll("DequeueInvariantLaws", InvariantTests[Dequeue[IO, *]].invariant[Int, Int, String])
  }

  {
    implicit val ticker = Ticker()
    implicit def eqByDequeSource[A: Eq]: Eq[DequeueSource[IO, A]] =
      getDequeueEq[A, DequeueSource[IO, A]]
    checkAll("DequeueFunctorLaws", FunctorTests[DequeueSource[IO, *]].functor[Int, Int, String])
  }

  implicit def arbQueueDequeue[A: Arbitrary](implicit ticker: Ticker): Arbitrary[Dequeue[IO, A]] =
    Arbitrary(genDequeue)

  implicit def arbDequeueSource[A: Arbitrary](implicit ticker: Ticker): Arbitrary[DequeueSource[IO, A]] =
    Arbitrary(genDequeue)

  private def fromList[A: Arbitrary](as: List[A]): IO[Dequeue[IO, A]] = {
    for {
      dequeue <- Dequeue.bounded[IO, A](Int.MaxValue)
      _ <- as.traverse(a => dequeue.offer(a))
    } yield dequeue
  }

  private def toListSource[A](q: Dequeue[IO, A])(implicit ticker: Ticker): List[A] = {
    val res = for {
      size <- q.size
      list <-
        if (size == 0) {
          IO.pure(List.empty)
        } else {
          q.tryTakeN(size.some)
        }
    } yield list
    unsafeRun(res) match {
      case Outcome.Succeeded(a) => a.get
      case Outcome.Errored(e) =>
        throw new Exception(s"Run error: $e")
      case Outcome.Canceled() =>
        throw new Exception("Canceled")
    }
  }

  private def getDequeueEq[A: Eq, D <: DequeueSource[IO, A]](implicit ticker: Ticker): Eq[D] =
    (x: D, y: D) =>
      {
        unsafeRun {
          for {
            xSizeBeforeTake <- x.size
            ySizeBeforeTake <- y.size
            _ <- if (xSizeBeforeTake > 0) x.tryTakeN(xSizeBeforeTake.some) else IO.pure(List())
            xSizeAfterTake <- x.size
            ySizeAfterTake <- y.size
          } yield xSizeBeforeTake === ySizeBeforeTake && xSizeAfterTake == ySizeAfterTake
        } match {
          case Outcome.Succeeded(a) => a
          case Outcome.Errored(e) =>
            throw new Exception(s"Run error: $e")
          case Outcome.Canceled() =>
            throw new Exception("Canceled")
        }
      }.get

  private def genDequeue[A: Arbitrary](implicit ticker: Ticker): Gen[Dequeue[IO, A]] = {
    for {
      list <- Arbitrary.arbitrary[List[A]]
      outcome = unsafeRun(fromList(list)) match {
        case Outcome.Succeeded(a) => a
        case _ => None
      }
    } yield outcome.get
  }
}
