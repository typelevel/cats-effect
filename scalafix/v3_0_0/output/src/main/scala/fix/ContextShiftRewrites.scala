package fix

import cats.effect.Sync

object ContextShiftRewrites {
  def f1[F[_]]: Int = 0

  // TODO
  //def f2[F[_]](implicit cs: ContextShift[F], F: Sync[F]): Int = 0
}
