package fix

import cats.effect.IO
import cats.effect.Sync

object BlockerRewrites {
  def f1: Int = 0

  def f1_1(i: Int): Int = i

  def f2: IO[Unit] =
    Sync[IO].blocking(())

  def f3[F[_]](implicit F: Sync[F]): F[Unit] =
    Sync[F].blocking(())

  def f4[F[_]](implicit F: Sync[F]): F[Unit] =
    Sync[F].blocking(())
}
