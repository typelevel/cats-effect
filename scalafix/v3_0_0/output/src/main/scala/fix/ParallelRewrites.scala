package fix

import cats.Parallel
import cats.effect.Concurrent
import cats.effect.implicits._

object ParallelRewrites {
  def f1[F[_]](implicit F: Concurrent[F]): Unit = ()
}
