package fix

import cats.effect.Async
import cats.effect.Sync
import cats.effect.Spawn

object ContextShiftRewrites {
  def f1[F[_]]: Int = 0

  def f2[F[_]](implicit F: Sync[F]): Int = 0

  def f3[F[_]](implicit F: Sync[F]): Int = 0

  def f4[F[_]](implicit F1: Sync[F], F2: Sync[F]): Int = 0

  def f5[F[_]](implicit F: Async[F]): F[Unit] = Spawn[F].cede
}
