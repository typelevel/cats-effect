/*
 * Copyright 2020-2024 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect.std

import cats.{Hash, Show}
import cats.data.EitherT
import cats.effect.{BaseSpec, IO, Ref, Temporal}
import cats.mtl.Handle
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroup._

import scala.concurrent.duration._

class RetrySpec extends BaseSpec {
  import Retry.{Decision, Status}

  private class Error1 extends RuntimeException
  private class Error2 extends RuntimeException

  private val error: Throwable = new RuntimeException("oops")
  private val errorIO: IO[Unit] = IO.raiseError(error)

  "Retry" should {

    "retry until the action succeeds" in ticked { implicit ticker =>
      val delay = 1.second
      val policy = Retry.constantDelay[IO, Throwable](delay)

      def action(attempts: Ref[IO, Int]) =
        attempts.getAndUpdate(_ + 1).flatMap(total => errorIO.whenA(total < 3))

      val io =
        for {
          counter <- IO.ref(0)
          result <- action(counter).retry(policy).timed
          counterValue <- counter.get
        } yield (result._1, counterValue)

      val expectedRetries = 3

      io must completeAs((delay * expectedRetries.toLong, expectedRetries + 1))
    }

    "retry until the policy chooses to give up" in ticked { implicit ticker =>
      val maxRetries = 2
      val policy = Retry.maxRetries[IO, Throwable](maxRetries)

      val expected = List(
        RetryAttempt(Status(0, Duration.Zero), Decision.retry(Duration.Zero)),
        RetryAttempt(Status(1, Duration.Zero), Decision.retry(Duration.Zero)),
        RetryAttempt(Status(2, Duration.Zero), Decision.giveUp)
      )

      run(policy)(errorIO) must completeAs(expected)
    }

    "withCappedDelay - cap the individual delay" in ticked { implicit ticker =>
      val maxRetries = 5
      val delay = 2.second
      val capDelay = 1.second

      val policy = Retry
        .constantDelay[IO, Throwable](delay)
        .withCappedDelay(capDelay)
        .withMaxRetries(maxRetries)

      val expected = {
        val retries = List.tabulate(maxRetries) { i =>
          RetryAttempt(Status(i, capDelay * i.toLong), Decision.retry(capDelay))
        }

        retries :+ RetryAttempt(Status(maxRetries, maxRetries * capDelay), Decision.giveUp)
      }

      run(policy)(errorIO) must completeAs(expected)
    }

    "withMaxRetries - give up once max retries are reached" in ticked { implicit ticker =>
      val maxRetries = 5
      val delay = 2.second

      val policy =
        Retry.constantDelay[IO, Throwable](delay).withMaxRetries(maxRetries)

      val expected = {
        val retries = List.tabulate(maxRetries) { i =>
          RetryAttempt(Status(i, delay * i.toLong), Decision.retry(delay))
        }

        retries :+ RetryAttempt(Status(maxRetries, maxRetries * delay), Decision.giveUp)
      }

      run(policy)(errorIO) must completeAs(expected)
    }

    "withMaxDelay - give up once max individual delay is reached" in ticked { implicit ticker =>
      val delay = 1.second
      val maxDelay = 5.second

      val policy =
        Retry[IO, Throwable] { (status, _) =>
          IO.pure(Decision.retry(status.retriesTotal * delay))
        }.withMaxDelay(maxDelay)

      val expected = {
        val numRetries = (maxDelay.toSeconds / delay.toSeconds).toInt

        val retries = List.tabulate(numRetries) { i =>
          val cumulativeDelay =
            Range(0, i).map(_ * delay).reduceOption(_ + _).getOrElse(Duration.Zero)

          RetryAttempt(Status(i, cumulativeDelay), Decision.retry(i * delay))
        }

        val cumulativeDelay = retries.map(_.status.cumulativeDelay).reduce(_ + _)
        retries :+ RetryAttempt(Status(numRetries, cumulativeDelay), Decision.giveUp)
      }

      run(policy)(errorIO) must completeAs(expected)
    }

    "withMaxCumulativeDelay - cap the max cumulative (total) delay" in ticked { implicit t =>
      val delay = 1.second
      val maxDelay = 5.second
      val maxRetries = (maxDelay.toSeconds / delay.toSeconds).toInt - 1

      val policy =
        Retry.constantDelay[IO, Throwable](delay).withMaxCumulativeDelay(maxDelay)

      val expected = {
        val retries = List.tabulate(maxRetries) { i =>
          RetryAttempt(Status(i, delay * i.toLong), Decision.retry(delay))
        }

        retries :+ RetryAttempt(Status(maxRetries, delay * maxRetries.toLong), Decision.giveUp)
      }

      run(policy)(errorIO) must completeAs(expected)
    }

    "&& (and) - give up when one policy is decided to give up" in ticked { implicit ticker =>
      val alwaysGiveUp = Retry.alwaysGiveUp[IO, Throwable]
      val alwaysRetry = Retry.constantDelay[IO, Throwable](1.second)

      val expected = Decision.giveUp

      (alwaysGiveUp |+| alwaysRetry).decide(Status.initial, error) must completeAs(expected)
    }

    "&& (and) - choose the maximum delay if both policies decided to retry" in ticked {
      implicit ticker =>
        val delay1 = Retry.constantDelay[IO, Throwable](1.second)
        val delay2 = Retry.constantDelay[IO, Throwable](2.seconds)

        val expected = Decision.retry(2.seconds)

        (delay1 |+| delay2).decide(Status.initial, error) must completeAs(expected)
    }

    "|| (or) - retry when one policy decided to retry" in ticked { implicit ticker =>
      val alwaysGiveUp = Retry.alwaysGiveUp[IO, Throwable]
      val alwaysRetry = Retry.constantDelay[IO, Throwable](1.second)

      val expected = Decision.retry(1.second)

      (alwaysGiveUp || alwaysRetry).decide(Status.initial, error) must completeAs(expected)
    }

    "|| (or) - choose the minimum delay if both policies decided to retry" in ticked {
      implicit ticker =>
        val delay1 = Retry.constantDelay[IO, Throwable](1.second)
        val delay2 = Retry.constantDelay[IO, Throwable](2.seconds)

        val expected = Decision.retry(1.second)

        (delay1 || delay2).decide(Status.initial, error) must completeAs(expected)
    }

  }

  "Retry#withErrorMatcher" should {

    "retry only on matched errors" in ticked { implicit ticker =>
      val maxRetries = 2
      val delay = 1.second

      val error = new Error1
      val policy = Retry
        .constantDelay[IO, Throwable](delay)
        .withMaxRetries(maxRetries)
        .withErrorMatcher(Retry.ErrorMatcher[IO, Throwable].only[Error1])

      val expected = List(
        RetryAttempt(Status(0, Duration.Zero), Decision.retry(delay), error),
        RetryAttempt(Status(1, 1.second), Decision.retry(delay), error),
        RetryAttempt(Status(2, 2.seconds), Decision.giveUp, error)
      )

      run(policy)(IO.raiseError(error)) must completeAs(expected)
    }

    "give up on mismatched errors" in ticked { implicit ticker =>
      val maxRetries = 5
      val delay = 1.second

      val policy = Retry
        .constantDelay[IO, Throwable](delay)
        .withMaxRetries(maxRetries)
        .withErrorMatcher(Retry.ErrorMatcher[IO, Throwable].only[Error1])

      val expected = List(
        RetryAttempt(Status(0, Duration.Zero), Decision.giveUp)
      )

      run(policy)(errorIO) must completeAs(expected)
    }

    "keep the last matcher - give up on mismatched" in ticked { implicit ticker =>
      val delay = 1.second
      val maxRetries = 1

      val policy = Retry
        .constantDelay[IO, Throwable](delay)
        .withMaxRetries(maxRetries)
        .withErrorMatcher(Retry.ErrorMatcher[IO, Throwable].only[Error1])
        .withErrorMatcher(Retry.ErrorMatcher[IO, Throwable].only[Error2])

      val error = new Error1
      val expected = List(
        RetryAttempt(Status(0, Duration.Zero), Decision.giveUp, error)
      )

      run(policy)(IO.raiseError(error)) must completeAs(expected)
    }

    "keep the last matcher - retry on matching errors" in ticked { implicit ticker =>
      val delay = 1.second
      val maxRetries = 2

      val policy = Retry
        .constantDelay[IO, Throwable](delay)
        .withMaxRetries(maxRetries)
        .withErrorMatcher(Retry.ErrorMatcher[IO, Throwable].only[Error1])
        .withErrorMatcher(Retry.ErrorMatcher[IO, Throwable].only[Error2])

      val error = new Error2
      val expected = List(
        RetryAttempt(Status(0, Duration.Zero), Decision.retry(delay), error),
        RetryAttempt(Status(1, 1.second), Decision.retry(delay), error),
        RetryAttempt(Status(2, 2.seconds), Decision.giveUp, error)
      )

      run(policy)(IO.raiseError(error)) must completeAs(expected)
    }

    "ErrorMatcher.except - give up on 'excepted' errors" in ticked { implicit ticker =>
      val maxRetries = 2
      val delay = 1.second

      val error = new Error1
      val policy = Retry
        .constantDelay[IO, Throwable](delay)
        .withMaxRetries(maxRetries)
        .withErrorMatcher(Retry.ErrorMatcher[IO, Throwable].except[Error1])

      val expected = List(
        RetryAttempt(Status(0, Duration.Zero), Decision.giveUp, error)
      )

      run(policy)(IO.raiseError(error)) must completeAs(expected)
    }

    "ErrorMatcher.except - give up on subtypes" in ticked { implicit ticker =>
      val maxRetries = 2
      val delay = 1.second

      val error = new Error1
      val policy = Retry
        .constantDelay[IO, Throwable](delay)
        .withMaxRetries(maxRetries)
        .withErrorMatcher(Retry.ErrorMatcher[IO, Throwable].except[RuntimeException])

      val expected = List(
        RetryAttempt(Status(0, Duration.Zero), Decision.giveUp, error)
      )

      run(policy)(IO.raiseError(error)) must completeAs(expected)
    }

    "ErrorMatcher.except - recover on all errors but the 'excepted' one" in ticked {
      implicit ticker =>
        val maxRetries = 2
        val delay = 1.second

        val error = new Error2
        val policy = Retry
          .constantDelay[IO, Throwable](delay)
          .withMaxRetries(maxRetries)
          .withErrorMatcher(Retry.ErrorMatcher[IO, Throwable].except[Error1])

        val expected = List(
          RetryAttempt(Status(0, Duration.Zero), Decision.retry(delay), error),
          RetryAttempt(Status(1, 1.second), Decision.retry(delay), error),
          RetryAttempt(Status(2, 2.seconds), Decision.giveUp, error)
        )

        run(policy)(IO.raiseError(error)) must completeAs(expected)
    }

  }

  "Retry.exponentialBackoff" should {
    // it's not random :)
    val RandomNextDouble = 1.0
    implicit val random: Random[IO] =
      new Random.ScalaRandom[IO](IO.pure(scala.util.Random)) {
        override def nextDouble: IO[Double] = IO.pure(RandomNextDouble)
      }

    def calculateExpected(
        baseDelay: FiniteDuration,
        maxRetries: Int,
        multiplier: Double,
        factor: Double,
        maxDelay: Option[FiniteDuration] = None
    ): List[RetryAttempt] = {

      // per step delay = rand_factor * random_double [0:1] * (base_delay * multiplier ^ retry)
      def stepDelay(retry: Int) =
        factor * RandomNextDouble * baseDelay * math.pow(multiplier, retry.toDouble) match {
          case f: FiniteDuration =>
            maxDelay.fold(f)(f.min)
          case _ =>
            sys.error("result is not finite")
        }

      @annotation.tailrec
      def loop(retry: Int, output: List[RetryAttempt]): List[RetryAttempt] = {
        val cumulative = output
          .collect { case RetryAttempt(_, r: Decision.Retry, _) => r.delay }
          .reduceOption(_ + _)
          .getOrElse(Duration.Zero)

        val status = Status(retry, cumulative)

        if (retry < maxRetries) {
          val next = RetryAttempt(status, Decision.retry(stepDelay(retry)))
          loop(retry + 1, output :+ next)
        } else {
          output :+ RetryAttempt(status, Decision.giveUp)
        }
      }

      loop(0, Nil)
    }

    "use default multiplier (2.0) and randomization factor (0.5)" in ticked { implicit ticker =>
      val baseDelay = 1.second
      val maxRetries = 3
      val policy = Retry.exponentialBackoff[IO, Throwable](baseDelay).withMaxRetries(maxRetries)

      val expected = calculateExpected(
        baseDelay = baseDelay,
        maxRetries = maxRetries,
        multiplier = 2.0,
        factor = 0.5
      )

      run(policy)(errorIO) must completeAs(expected)
    }

    "use the given multiplier (1.0) and randomization factor (1.0)" in ticked { implicit t =>
      val baseDelay = 1.second
      val maxRetries = 3
      val policy = Retry
        .exponentialBackoff[IO, Throwable](
          baseDelay,
          Some(Retry.BackoffMultiplier.const(1.0)),
          Some(1.0)
        )
        .withMaxRetries(maxRetries)

      val expected = calculateExpected(
        baseDelay = baseDelay,
        maxRetries = maxRetries,
        multiplier = 1.0,
        factor = 1.0
      )

      run(policy)(errorIO) must completeAs(expected)
    }

    "cap the delay at the specific max" in ticked { implicit ticker =>
      val baseDelay = 1.second
      val maxRetries = 5
      val maxDelay = 2.seconds
      val policy = Retry
        .exponentialBackoff[IO, Throwable](baseDelay)
        .withCappedDelay(maxDelay)
        .withMaxRetries(maxRetries)

      val expected = calculateExpected(
        baseDelay = baseDelay,
        maxRetries = maxRetries,
        multiplier = 2.0,
        factor = 0.5,
        maxDelay = Some(maxDelay)
      )

      run(policy)(errorIO) must completeAs(expected)
    }

    "respect the max number of retries" in ticked { implicit ticker =>
      val baseDelay = 1.second
      val maxRetries = 5
      val maxDelay = 2.seconds
      val policy = Retry
        .exponentialBackoff[IO, Throwable](baseDelay)
        .withCappedDelay(maxDelay)
        .withMaxRetries(maxRetries)

      val expected = calculateExpected(
        baseDelay = baseDelay,
        maxRetries = maxRetries,
        multiplier = 2.0,
        factor = 0.5,
        maxDelay = Some(maxDelay)
      )

      run(policy)(errorIO) must completeAs(expected)
    }

  }

  "Retry MTL" should {

    sealed trait Errors
    final class Error1 extends Errors
    final class Error2 extends Errors

    type RetryAttempt = (Status, Decision, Errors)

    def mtlRetry[F[_], E, A](
        action: F[A],
        policy: Retry[F, E],
        onRetry: (Status, E, Decision) => F[Unit]
    )(implicit F: Temporal[F], H: Handle[F, E]): F[A] =
      F.tailRecM(Status.initial) { status =>
        H.attempt(action).flatMap {
          case Left(error) =>
            policy
              .decide(status, error)
              .flatTap(decision => onRetry(status, error, decision))
              .flatMap {
                case retry: Decision.Retry =>
                  F.delayBy(F.pure(Left(status.withRetry(retry.delay))), retry.delay)

                case _: Decision.GiveUp =>
                  H.raise(error)
              }

          case Right(success) =>
            F.pure(Right(success))
        }
      }

    implicit val outputHash: Hash[(Either[Errors, Unit], List[RetryAttempt])] =
      Hash.fromUniversalHashCode

    implicit val outputShow: Show[(Either[Errors, Unit], List[RetryAttempt])] =
      Show.fromToString

    "give up on mismatched errors" in ticked { implicit ticker =>
      type F[A] = EitherT[IO, Errors, A]

      val maxRetries = 2
      val delay = 1.second

      val error = new Error2
      val policy = Retry
        .constantDelay[F, Errors](delay)
        .withMaxRetries(maxRetries)
        .withErrorMatcher(Retry.ErrorMatcher[F, Errors].only[Error1])

      val expected: List[RetryAttempt] = List(
        (Status(0, Duration.Zero), Decision.giveUp, error)
      )

      val io: F[Unit] = Handle[F, Errors].raise[Errors, Unit](error)

      val run =
        for {
          ref <- IO.ref(List.empty[RetryAttempt])
          result <- mtlRetry[F, Errors, Unit](
            io,
            policy,
            (s, e: Errors, d) => EitherT.liftF(ref.update(_ :+ (s, d, e)))
          ).value
          attempts <- ref.get
        } yield (result, attempts)

      run must completeAs((Left(error), expected))
    }

    "retry only on matching errors" in ticked { implicit ticker =>
      type F[A] = EitherT[IO, Errors, A]

      val maxRetries = 2
      val delay = 1.second

      val error = new Error1
      val policy = Retry
        .constantDelay[F, Errors](delay)
        .withMaxRetries(maxRetries)
        .withErrorMatcher(Retry.ErrorMatcher[F, Errors].only[Error1])

      val expected: List[RetryAttempt] = List(
        (Status(0, Duration.Zero), Decision.retry(delay), error),
        (Status(1, 1.second), Decision.retry(delay), error),
        (Status(2, 2.seconds), Decision.giveUp, error)
      )

      val io: F[Unit] = Handle[F, Errors].raise[Errors, Unit](error)

      val run =
        for {
          ref <- IO.ref(List.empty[RetryAttempt])
          result <- mtlRetry[F, Errors, Unit](
            io,
            policy,
            (s, e: Errors, d) => EitherT.liftF(ref.update(_ :+ (s, d, e)))
          ).value
          attempts <- ref.get
        } yield (result, attempts)

      run must completeAs((Left(error), expected))
    }

  }

  private def run[A](policy: Retry[IO, Throwable])(io: IO[A]): IO[List[RetryAttempt]] =
    for {
      ref <- IO.ref(List.empty[RetryAttempt])
      time <- io
        .retry(policy, (s, e: Throwable, d) => ref.update(_ :+ RetryAttempt(s, d, e)))
        .attempt
        .timed
        ._1F
      attempts <- ref.get
    } yield {
      if (time > Duration.Zero && attempts.nonEmpty) { // ensure sleep time == cumulative delay
        assert(attempts.last.status.cumulativeDelay == time)
      }

      attempts
    }

  private case class RetryAttempt(
      status: Status,
      decision: Decision,
      error: Throwable = error
  )

  private implicit val retryAttemptHash: Hash[RetryAttempt] = Hash.fromUniversalHashCode
  private implicit val retryAttemptShow: Show[RetryAttempt] = Show.fromToString
  private implicit val decisionHash: Hash[Decision] = Hash.fromUniversalHashCode
  private implicit val decisionShow: Show[Decision] = Show.fromToString
}
