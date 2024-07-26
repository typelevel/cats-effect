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
import cats.effect.{BaseSpec, IO, Ref}
import cats.syntax.applicative._
import cats.syntax.semigroup._

import scala.concurrent.duration._

class RetrySpec extends BaseSpec {
  import Retry.{Decision, Status}

  private class Error1 extends RuntimeException

  private val error: Throwable = new RuntimeException("oops")
  private val errorIO: IO[Unit] = IO.raiseError(error)

  "Retry" should {

    "retry until the action succeeds" in ticked { implicit ticker =>
      val delay = 1.second
      val policy = Retry.constantDelay[IO, Throwable](delay)

      def action(attempts: Ref[IO, Int]) =
        attempts.getAndUpdate(_ + 1).flatMap { total =>
          IO.raiseError(new RuntimeException("oops")).whenA(total < 3)
        }

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
      val io = IO.raiseError(new RuntimeException("oops"))

      run(policy)(io) must completeAs((Duration.Zero, maxRetries + 1))
    }

    "withErrorMatcher - retry only on matched errors" in ticked { implicit ticker =>
      val maxRetries = 5
      val delay = 1.second

      val policy =
        Retry.constantDelay[IO, Throwable](delay).withMaxRetries(maxRetries).withErrorMatcher {
          case _: Error1 => IO.pure(true)
        }

      val io = IO.raiseError(new Error1)

      run(policy)(io) must completeAs((maxRetries * delay, maxRetries + 1))
    }

    "withErrorMatcher - give up on mismatched errors" in ticked { implicit ticker =>
      val maxRetries = 5
      val delay = 1.second

      val policy =
        Retry.constantDelay[IO, Throwable](delay).withMaxRetries(maxRetries).withErrorMatcher {
          case _: Error1 => IO.pure(true)
        }

      val io = IO.raiseError(new RuntimeException("oops"))

      run(policy)(io) must completeAs((Duration.Zero, 1))
    }

    "withCappedDelay - cap the individual delay" in ticked { implicit ticker =>
      val maxRetries = 5
      val delay = 2.second
      val capDelay = 1.second

      val policy =
        Retry
          .constantDelay[IO, Throwable](delay)
          .withCappedDelay(capDelay)
          .withMaxRetries(maxRetries)

      val expected: List[(Status, Decision)] = {
        val retries = List.tabulate(maxRetries) { i =>
          (Status(i, capDelay * i.toLong.toLong), Decision.retry(capDelay))
        }

        retries :+ (Status(maxRetries, maxRetries * capDelay) -> Decision.giveUp)
      }

      runWithAttempts(policy)(errorIO) must completeAs((maxRetries * capDelay, expected))
    }

    "withMaxRetries - give up once max retries are reached" in ticked { implicit ticker =>
      val maxRetries = 5
      val delay = 2.second

      val policy =
        Retry.constantDelay[IO, Throwable](delay).withMaxRetries(maxRetries)

      val expected: List[(Status, Decision)] = {
        val retries = List.tabulate(maxRetries) { i =>
          (Status(i, delay * i.toLong), Decision.retry(delay))
        }

        retries :+ (Status(maxRetries, maxRetries * delay) -> Decision.giveUp)
      }

      runWithAttempts(policy)(errorIO) must completeAs((maxRetries * delay, expected))
    }

    "withMaxDelay - give up once max individual delay is reached" in ticked { implicit ticker =>
      val delay = 1.second
      val maxDelay = 5.second

      val policy =
        Retry[IO, Throwable] { (status, _) =>
          IO.pure(Decision.retry(status.retriesTotal * delay))
        }.withMaxDelay(maxDelay)

      val expected: List[(Status, Decision)] = {
        val numRetries = (maxDelay.toSeconds / delay.toSeconds).toInt

        val retries = List.tabulate(numRetries) { i =>
          val cumulativeDelay =
            Range(0, i).map(_ * delay).reduceOption(_ + _).getOrElse(Duration.Zero)

          (Status(i, cumulativeDelay), Decision.retry(i * delay))
        }

        val cumulativeDelay = retries.map(_._1.cumulativeDelay).reduce(_ + _)
        retries :+ (Status(numRetries, cumulativeDelay) -> Decision.giveUp)
      }
      val totalDelay = expected.map(_._1.cumulativeDelay).last

      runWithAttempts(policy)(errorIO) must completeAs((totalDelay, expected))
    }

    "withMaxCumulativeDelay - cap the max cumulative (total) delay" in ticked { implicit t =>
      val delay = 1.second
      val maxDelay = 5.second
      val maxRetries = (maxDelay.toSeconds / delay.toSeconds).toInt - 1

      val policy =
        Retry.constantDelay[IO, Throwable](delay).withMaxCumulativeDelay(maxDelay)

      val expected: List[(Status, Decision)] = {
        val retries = List.tabulate(maxRetries) { i =>
          (Status(i, delay * i.toLong), Decision.retry(delay))
        }

        retries :+ (Status(maxRetries, delay * maxRetries.toLong) -> Decision.giveUp)
      }

      runWithAttempts(policy)(errorIO) must completeAs((maxRetries * delay, expected))
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
    ): List[(Status, Decision)] = {

      // per step delay = rand_factor * random_double [0:1] * (base_delay * multiplier ^ retry)
      def stepDelay(retry: Int) =
        factor * RandomNextDouble * baseDelay * math.pow(multiplier, retry.toDouble) match {
          case f: FiniteDuration =>
            maxDelay.fold(f)(f.min)
          case _ =>
            sys.error("result is not finite")
        }

      @annotation.tailrec
      def loop(retry: Int, output: List[(Status, Decision)]): List[(Status, Decision)] = {
        val cumulative = output
          .collect { case (_, r: Decision.Retry) => r.delay }
          .reduceOption(_ + _)
          .getOrElse(Duration.Zero)

        val status = Status(retry, cumulative)

        if (retry < maxRetries) {
          val next = (status, Decision.retry(stepDelay(retry)))
          loop(retry + 1, output :+ next)
        } else {
          output :+ (status -> Decision.giveUp)
        }
      }

      loop(0, Nil)
    }

    "use default multiplier (2.0) and randomization factor (0.5)" in ticked { implicit ticker =>
      val baseDelay = 1.second
      val maxRetries = 3
      val policy = Retry.exponentialBackoff[IO, Throwable](baseDelay).withMaxRetries(maxRetries)

      val expected: List[(Status, Decision)] = calculateExpected(
        baseDelay = baseDelay,
        maxRetries = maxRetries,
        multiplier = 2.0,
        factor = 0.5
      )
      val delayTotal = expected.map(_._1.cumulativeDelay).last

      runWithAttempts(policy)(errorIO) must completeAs((delayTotal, expected))
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

      val expected: List[(Status, Decision)] = calculateExpected(
        baseDelay = baseDelay,
        maxRetries = maxRetries,
        multiplier = 1.0,
        factor = 1.0
      )
      val delayTotal = expected.map(_._1.cumulativeDelay).last

      runWithAttempts(policy)(errorIO) must completeAs((delayTotal, expected))
    }

    "cap the delay at the specific max" in ticked { implicit ticker =>
      val baseDelay = 1.second
      val maxRetries = 5
      val maxDelay = 2.seconds
      val policy = Retry
        .exponentialBackoff[IO, Throwable](baseDelay)
        .withCappedDelay(maxDelay)
        .withMaxRetries(maxRetries)

      val expected: List[(Status, Decision)] = calculateExpected(
        baseDelay = baseDelay,
        maxRetries = maxRetries,
        multiplier = 2.0,
        factor = 0.5,
        maxDelay = Some(maxDelay)
      )
      val delayTotal = expected.map(_._1.cumulativeDelay).last

      runWithAttempts(policy)(errorIO) must completeAs((delayTotal, expected))
    }

    "respect the max number of retries" in ticked { implicit ticker =>
      val baseDelay = 1.second
      val maxRetries = 5
      val maxDelay = 2.seconds
      val policy = Retry
        .exponentialBackoff[IO, Throwable](baseDelay)
        .withCappedDelay(maxDelay)
        .withMaxRetries(maxRetries)

      val expected: List[(Status, Decision)] = calculateExpected(
        baseDelay = baseDelay,
        maxRetries = maxRetries,
        multiplier = 2.0,
        factor = 0.5,
        maxDelay = Some(maxDelay)
      )
      val delayTotal = expected.map(_._1.cumulativeDelay).last

      runWithAttempts(policy)(errorIO) must completeAs((delayTotal, expected))
    }

  }

  private def run[A](policy: Retry[IO, Throwable])(io: IO[A]): IO[(FiniteDuration, Int)] =
    for {
      counter <- IO.ref(0)
      result <- io.retry(policy, (_, _: Throwable, _) => counter.update(_ + 1)).attempt.timed
      attempts <- counter.get
    } yield (result._1, attempts)

  private def runWithAttempts[A](policy: Retry[IO, Throwable])(
      io: IO[A]
  ): IO[(FiniteDuration, List[(Status, Decision)])] =
    for {
      ref <- IO.ref(List.empty[(Status, Decision)])
      result <- io
        .retry(policy, (s, _: Throwable, d) => ref.update(_ :+ (s -> d)))
        .attempt
        .timed
      attempts <- ref.get
    } yield (result._1, attempts)

  private implicit val statusOrder: Hash[Status] = Hash.fromUniversalHashCode
  private implicit val statusShow: Show[Status] = Show.fromToString
  private implicit val decisionOrder: Hash[Decision] = Hash.fromUniversalHashCode
  private implicit val decisionShow: Show[Decision] = Show.fromToString
}
