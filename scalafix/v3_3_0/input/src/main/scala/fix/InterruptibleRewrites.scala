/*
rule = "scala:fix.v3_3_0"
 */
package fix

import cats.effect.{IO, Sync}

object InterruptibleRewrites {
  IO.interruptible(true)(IO.unit)

  IO.interruptible(false)(IO.unit)

  Sync[IO].interruptible(true)(IO.unit)

  Sync[IO].interruptible(false)(IO.unit)

  def f[F[_]](implicit F: Sync[F]): F[Unit] = F.interruptible(true)(IO.unit)
}
