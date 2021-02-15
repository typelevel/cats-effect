package fix

import cats.effect.Sync

object BlockerRewrites {
  def f1: Int = 0

  // TODO
  //def f2[F[_]](blocker: Blocker)(implicit cs: ContextShift[F], F: Sync[F]): F[Unit] =
  //  blocker.delay(())
}
