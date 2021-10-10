package fix

import cats.effect.{ IO, Sync }

object InterruptibleRewrites {
  IO.interruptibleMany(IO.unit)

  IO.interruptible(IO.unit)

  Sync[IO].interruptibleMany(IO.unit)

  Sync[IO].interruptible(IO.unit)

  def f[F[_]](implicit F: Sync[F]): F[Unit] = Sync[F].interruptibleMany(IO.unit)
}
