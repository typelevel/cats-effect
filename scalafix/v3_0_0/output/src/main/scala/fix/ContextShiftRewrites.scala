package fix

import cats.effect.Sync

object ContextShiftRewrites {
  def f1[F[_]]: Int = 0

  def f2[F[_]](implicit F: Sync[F]): Int = 0

  def f3[F[_]](implicit F: Sync[F]): Int = 0

  def f4[F[_]](implicit F1: Sync[F], F2: Sync[F]): Int = 0
}
