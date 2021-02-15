/*
rule = "scala:fix.v3_0_0"
 */
package fix

import cats.effect.Blocker
import cats.effect.ContextShift
import cats.effect.Sync

object BlockerRewrites {
  def f1(blocker: Blocker): Int = 0

  // TODO
  //def f2[F[_]](blocker: Blocker)(implicit cs: ContextShift[F], F: Sync[F]): F[Unit] =
  //  blocker.delay(())
}
