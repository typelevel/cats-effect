package cats.effect
package std

import cats.data._
import cats.effect.kernel.Async
import cats.effect.laws._
import cats.effect.syntax.all._
import cats.kernel.Eq
import cats.laws.discipline.arbitrary._
import cats.syntax.all._
import org.scalacheck._
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification
import org.typelevel.discipline.Laws
import org.typelevel.discipline.specs2.mutable.Discipline

import Prop.forAll

trait ResumeLaws[F[_]] {

  implicit val async: Async[F]
  implicit val resume: Resume[F]

  // Running the effect through unsafe async boundary to ensure
  // that algebraic semantics are accounted for by the Resume.
  //
  // In case of unaccounted coproduct semantics (side error channels), the unsafe
  // run will hang, making the test eventually timeout.
  //
  // In case of unaccounted product semantics (WriterT, IorT.Both), the unsafe
  // run will forget/lose the log channel.
  //
  // This law therefore verifies that the Resume materialises all monadic,
  // but non CE-related monadic information so that the outer effect contains only
  // CE semantics, which can be ran through a dispatcher without hanging.
  // The law also verifies that the absorption of the materialised effects
  // after the dispatch contains the same amount of information as the initial
  // effect.
  def accountsForAllButSideEffects[A](fa: F[A]) = {
    val throughUnsafeBoundary = Dispatcher[F].use { dispatcher =>
      resume.resume(fa).background.use {
        _.flatMap {
          case Outcome.Canceled() =>
            async.canceled *> async.never[A]
          case Outcome.Errored(e) =>
            async.raiseError[A](e)
          case Outcome.Succeeded(fa_) =>
            val ffuture = async.delay(dispatcher.unsafeToFuture(fa_))
            async.fromFuture(ffuture).flatMap {
              case Left(fa) => fa
              case Right((funit, a)) => funit.as(a)
            }
        }
      }
    }

    throughUnsafeBoundary <-> fa
  }

  def resumePureIsUnitAndA[A](a: A) = {
    resume.resume(async.pure(a)) <-> async.pure(Right((async.unit, a)))
  }

}

object ResumeLaws {
  def apply[F[_]](implicit F0: Async[F], R: Resume[F]): ResumeLaws[F] = new ResumeLaws[F] {
    val async: Async[F] = F0
    val resume: Resume[F] = R
  }
}

trait ResumeTests[F[_]] extends Laws {

  val laws: ResumeLaws[F]

  def resume[A](
      implicit ArbFA: Arbitrary[F[A]],
      ArbA: Arbitrary[A],
      EqFA: Eq[F[A]],
      EqResume: Eq[F[Either[F[A], (F[Unit], A)]]]): RuleSet = {
    new RuleSet {
      val name = "resume"
      val bases = Nil
      val parents = Seq()

      val props = Seq(
        "accountsForAllButSideEffects" -> forAll(laws.accountsForAllButSideEffects[A] _),
        "resumePureIsUnitAndA" -> forAll(laws.resumePureIsUnitAndA[A] _)
      )
    }
  }
}

object ResumeTests {
  def apply[F[_]](implicit F0: Async[F], R: Resume[F]): ResumeTests[F] =
    new ResumeTests[F] {
      val laws = ResumeLaws[F]
    }
}

class ResumeSpec extends Specification with Discipline with ScalaCheck with BaseSpec {
  outer =>

  // we just need this because of the laws testing, since the prop runs can interfere with each other
  sequential

  implicit def kleisliEq[A](implicit eqIOA: Eq[IO[A]]): Eq[Kleisli[IO, Int, A]] =
    Eq.by[Kleisli[IO, Int, A], IO[A]](_.run(0))

  {
    implicit val ticker = Ticker()

    checkAll("OptionT[IO, *]", ResumeTests[OptionT[IO, *]].resume[Int])
    checkAll("IorT[IO, Int, *]", ResumeTests[IorT[IO, Int, *]].resume[Int])
    checkAll("WriterT[IO, Int, *]", ResumeTests[WriterT[IO, Int, *]].resume[Int])
    checkAll("EitherT[IO, Int, *]", ResumeTests[EitherT[IO, Int, *]].resume[Int])
    checkAll("Kleisli[IO, Int, *]", ResumeTests[Kleisli[IO, Int, *]].resume[Int])
    checkAll("IO", ResumeTests[IO].resume[Int])
    // Laws breaks on first attempt with seed
    // 0HGJ9GYf2lulgEEt7ykq8Mknz0uqEm-gkp_FKLoXiMJ=
    // checkAll("Resource[IO, *]", ResumeTests[Resource[IO, *]].resume[Int])
  }

}
