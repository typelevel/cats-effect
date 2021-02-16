/*
rule = "scala:fix.v3_0_0"
 */
package fix

import cats.effect.Blocker
import cats.effect.ContextShift
import cats.effect.IO
import cats.effect.Sync

object BlockerRewrites {
  def f1(blocker: Blocker): Int = 0

  def f1_1(blocker: Blocker, i: Int): Int = i

  def f2(blocker: Blocker)(implicit cs: ContextShift[IO]): IO[Unit] =
    blocker.delay[IO, Unit](())

  def f3[F[_]](blocker: Blocker)(implicit cs: ContextShift[F], F: Sync[F]): F[Unit] =
    blocker.delay[F, Unit](())

  def f4[F[_]](blocker: Blocker)(implicit cs: ContextShift[F], F: Sync[F]): F[Unit] =
    blocker.delay(())

  private val b1 = Blocker[IO]
}
