package cats.effect.std.syntax

import cats.effect.kernel.Temporal
import cats.effect.std.Retry

trait RetrySyntax {
  implicit def retryOps[F[_], A](wrapped: F[A]): RetryOps[F, A] =
    new RetryOps(wrapped)
}

final class RetryOps[F[_], A] private[syntax] (private[syntax] val wrapped: F[A])
    extends AnyVal {
  def retry(r: Retry[F])(implicit F: Temporal[F]): F[A] =
    Retry.retry(r, wrapped)
}
