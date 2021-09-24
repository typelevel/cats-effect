package fix

import cats.effect.{ IO, Sync }

object IORewrites {
  IO.interruptibleMany(IO.unit)

  IO.interruptible(IO.unit)

  Sync[IO].interruptibleMany(IO.unit)

  Sync[IO].interruptible(IO.unit)
}
