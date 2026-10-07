/*
 * Copyright 2020-2025 Typelevel
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
import cats.effect.{BaseSuite, IO, Ref}
import cats.syntax.applicative._
import cats.syntax.functor._
import cats.syntax.semigroup._

import scala.concurrent.duration._

class RetrySuite extends BaseSuite {
  import Retry.{Decision, Status}

  private class Error1 extends RuntimeException
  private class Error2 extends RuntimeException

  private val error: Throwable = new RuntimeException("oops")
  private val errorIO: IO[Unit] = IO.raiseError(error)

  ticked("retry until the action succeeds") { implicit ticker =>
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

    assertCompleteAs(io, (delay * expectedRetries.toLong, expectedRetries + 1))
  }

  ticked("retry until the policy chooses to give up") { implicit ticker =>
    val maxRetries = 2
    val policy = Retry.maxRetries[IO, Throwable](maxRetries)

    val expected = List(
      RetryAttempt(Status(0, Duration.Zero), Decision.retry(Duration.Zero)),
      RetryAttempt(Status(1, Duration.Zero), Decision.retry(Duration.Zero)),
      RetryAttempt(Status(2, Duration.Zero), Decision.giveUp)
    )

    assertCompleteAs(run(policy)(errorIO), expected)
  }

  ticked("withCappedDelay - cap the individual delay") { implicit ticker =>
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

    assertCompleteAs(run(policy)(errorIO), expected)
  }

  ticked("withMaxRetries - give up once max retries are reached") { implicit ticker =>
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

    assertCompleteAs(run(policy)(errorIO), expected)
  }

  ticked("withMaxDelay - give up once max individual delay is reached") { implicit ticker =>
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

    assertCompleteAs(run(policy)(errorIO), expected)
  }

  ticked("withMaxCumulativeDelay - cap the max cumulative (total) delay") { implicit t =>
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

    assertCompleteAs(run(policy)(errorIO), expected)
  }

  ticked("&& (and) - give up when one policy is decided to give up") { implicit ticker =>
    val alwaysGiveUp = Retry.alwaysGiveUp[IO, Throwable]
    val alwaysRetry = Retry.constantDelay[IO, Throwable](1.second)

    val expected = Decision.giveUp

    assertCompleteAs((alwaysGiveUp |+| alwaysRetry).decide(Status.initial, error), expected)
  }

  ticked("&& (and) - choose the maximum delay if both policies decided to retry") {
    implicit ticker =>
      val delay1 = Retry.constantDelay[IO, Throwable](1.second)
      val delay2 = Retry.constantDelay[IO, Throwable](2.seconds)

      val expected = Decision.retry(2.seconds)

      assertCompleteAs((delay1 |+| delay2).decide(Status.initial, error), expected)
  }

  ticked("|| (or) - retry when one policy decided to retry") { implicit ticker =>
    val alwaysGiveUp = Retry.alwaysGiveUp[IO, Throwable]
    val alwaysRetry = Retry.constantDelay[IO, Throwable](1.second)

    val expected = Decision.retry(1.second)

    assertCompleteAs((alwaysGiveUp || alwaysRetry).decide(Status.initial, error), expected)
  }

  ticked("|| (or) - choose the minimum delay if both policies decided to retry") {
    implicit ticker =>
      val delay1 = Retry.constantDelay[IO, Throwable](1.second)
      val delay2 = Retry.constantDelay[IO, Throwable](2.seconds)

      val expected = Decision.retry(1.second)

      assertCompleteAs((delay1 || delay2).decide(Status.initial, error), expected)
  }

  ticked("withErrorMatcher - retry only on matched errors") { implicit ticker =>
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

    assertCompleteAs(run(policy)(IO.raiseError(error)), expected)
  }

  ticked("withErrorMatcher - give up on mismatched errors") { implicit ticker =>
    val maxRetries = 5
    val delay = 1.second

    val policy = Retry
      .constantDelay[IO, Throwable](delay)
      .withMaxRetries(maxRetries)
      .withErrorMatcher(Retry.ErrorMatcher[IO, Throwable].only[Error1])

    val expected = List(
      RetryAttempt(Status(0, Duration.Zero), Decision.giveUp)
    )

    assertCompleteAs(run(policy)(errorIO), expected)
  }

  ticked("withErrorMatcher - keep the last matcher - give up on mismatched") {
    implicit ticker =>
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

      assertCompleteAs(run(policy)(IO.raiseError(error)), expected)
  }

  ticked("withErrorMatcher - keep the last matcher - retry on matching errors") {
    implicit ticker =>
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

      assertCompleteAs(run(policy)(IO.raiseError(error)), expected)
  }

  ticked("ErrorMatcher.except - give up on 'excepted' errors") { implicit ticker =>
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

    assertCompleteAs(run(policy)(IO.raiseError(error)), expected)
  }

  ticked("ErrorMatcher.except - give up on subtypes") { implicit ticker =>
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

    assertCompleteAs(run(policy)(IO.raiseError(error)), expected)
  }

  ticked("ErrorMatcher.except - recover on all errors but the 'excepted' one") {
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

      assertCompleteAs(run(policy)(IO.raiseError(error)), expected)
  }

  ticked("exponentialBackoff - use default multiplier (2.0) and randomization factor (0.5)") {
    implicit ticker =>
      val baseDelay = 1.second
      val maxRetries = 3
      val policy =
        Retry.exponentialBackoff[IO, Throwable](baseDelay).withMaxRetries(maxRetries)

      val expected = calculateExpectedBackoff(
        baseDelay = baseDelay,
        maxRetries = maxRetries,
        multiplier = 2.0,
        factor = 0.5
      )

      assertCompleteAs(run(policy)(errorIO), expected)
  }

  ticked("exponentialBackoff - use the given multiplier (1.0) and randomization factor (1.0)") {
    implicit ticker =>
      val baseDelay = 1.second
      val maxRetries = 3
      val policy = Retry
        .exponentialBackoff[IO, Throwable](
          baseDelay,
          Some(Retry.BackoffMultiplier.const(1.0)),
          Some(1.0)
        )
        .withMaxRetries(maxRetries)

      val expected = calculateExpectedBackoff(
        baseDelay = baseDelay,
        maxRetries = maxRetries,
        multiplier = 1.0,
        factor = 1.0
      )

      assertCompleteAs(run(policy)(errorIO), expected)
  }

  ticked("exponentialBackoff - cap the delay at the specific max") { implicit ticker =>
    val baseDelay = 1.second
    val maxRetries = 5
    val maxDelay = 2.seconds
    val policy = Retry
      .exponentialBackoff[IO, Throwable](baseDelay)
      .withCappedDelay(maxDelay)
      .withMaxRetries(maxRetries)

    val expected = calculateExpectedBackoff(
      baseDelay = baseDelay,
      maxRetries = maxRetries,
      multiplier = 2.0,
      factor = 0.5,
      maxDelay = Some(maxDelay)
    )

    assertCompleteAs(run(policy)(errorIO), expected)
  }

  ticked("exponentialBackoff - respect the max number of retries") { implicit ticker =>
    val baseDelay = 1.second
    val maxRetries = 5
    val maxDelay = 2.seconds
    val policy = Retry
      .exponentialBackoff[IO, Throwable](baseDelay)
      .withCappedDelay(maxDelay)
      .withMaxRetries(maxRetries)

    val expected = calculateExpectedBackoff(
      baseDelay = baseDelay,
      maxRetries = maxRetries,
      multiplier = 2.0,
      factor = 0.5,
      maxDelay = Some(maxDelay)
    )

    assertCompleteAs(run(policy)(errorIO), expected)
  }

  // it's not random :)
  private val RandomNextDouble = 1.0
  private implicit val random: Random[IO] =
    new Random.ScalaRandom[IO](IO.pure(scala.util.Random)) {
      override def nextDouble: IO[Double] = IO.pure(RandomNextDouble)
    }

  private def calculateExpectedBackoff(
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
