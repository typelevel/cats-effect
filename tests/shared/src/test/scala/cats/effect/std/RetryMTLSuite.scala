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
import cats.data.EitherT
import cats.effect.{BaseSuite, IO, Temporal}
import cats.mtl.Handle
import cats.syntax.all._

import scala.concurrent.duration._

class RetryMTLSuite extends BaseSuite {
  import Retry.{Decision, Status}

  sealed trait Errors
  final class Error1 extends Errors
  final class Error2 extends Errors

  type RetryAttempt = (Status, Decision, Errors)

  ticked("give up on mismatched errors") { implicit ticker =>
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
          (s, e: Errors, d) => EitherT.liftF(ref.update(_ :+ ((s, d, e))))
        ).value
        attempts <- ref.get
      } yield (result, attempts)

    assertCompleteAs(run, (Left(error), expected))
  }

  ticked("retry only on matching errors") { implicit ticker =>
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
          (s, e: Errors, d) => EitherT.liftF(ref.update(_ :+ ((s, d, e))))
        ).value
        attempts <- ref.get
      } yield (result, attempts)

    assertCompleteAs(run, (Left(error), expected))
  }

  private def mtlRetry[F[_], E, A](
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

  private implicit val outputHash: Hash[(Either[Errors, Unit], List[RetryAttempt])] =
    Hash.fromUniversalHashCode

  private implicit val outputShow: Show[(Either[Errors, Unit], List[RetryAttempt])] =
    Show.fromToString

}
