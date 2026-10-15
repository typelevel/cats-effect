package cats.effect

package std

import cats.Eq
import cats.effect.*
import cats.effect.kernel.Outcome
import cats.effect.testkit.TestInstances
import cats.laws.discipline.{FunctorTests, InvariantTests}
import cats.syntax.all.*
import org.scalacheck.{Arbitrary, Gen}
import munit.DisciplineSuite

class QueueLawsSuite extends BaseSuite with DisciplineSuite with TestInstances {
  {
    implicit val ticker = Ticker()
    implicit def queueEq[A: Eq]: Eq[Queue[IO, A]] = getQueueEq[A, Queue[IO, A]]

    checkAll("QueueInvariantLaws", InvariantTests[Queue[IO, *]].invariant[Int, Int, String])
  }

  {
    implicit val ticker = Ticker()
    implicit def eqByQueueSource[A: Eq]: Eq[QueueSource[IO, A]] = getQueueEq[A, QueueSource[IO, A]]
    checkAll("QueueFunctorLaws", FunctorTests[QueueSource[IO, *]].functor[Int, Int, String])
  }

  implicit def arbQueue[A: Arbitrary](implicit ticker: Ticker): Arbitrary[Queue[IO, A]] =
    Arbitrary(genQueue)

  implicit def arbQueueSource[A: Arbitrary](implicit ticker: Ticker): Arbitrary[QueueSource[IO, A]] =
    Arbitrary(genQueue)

  private def getQueueEq[A: Eq, Q <: QueueSource[IO, A]](implicit ticker: Ticker): Eq[Q] = {
    (x: Q, y: Q) =>
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
  }

  private def genQueue[A: Arbitrary](implicit ticker: Ticker): Gen[Queue[IO, A]] = {
    for {
      list <- Arbitrary.arbitrary[List[A]]
      outcome = unsafeRun(fromList(list)) match {
        case Outcome.Succeeded(a) => a
        case _ => None
      }
    } yield outcome.get
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

  private def fromList[A: Arbitrary](as: List[A]): IO[Queue[IO, A]] = {
    for {
      queue <- Queue.bounded[IO, A](Int.MaxValue)
      _     <- as.traverse(a => queue.offer(a))
    } yield queue
  }
}
