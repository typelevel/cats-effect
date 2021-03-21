/*
rule = "scala:fix.v3_0_0"
 */
package fix

import cats.Parallel
import cats.effect.Concurrent

object ParallelRewrites {
  def f1[F[_]](implicit p: Parallel[F], F: Concurrent[F]): Unit = ()
}
