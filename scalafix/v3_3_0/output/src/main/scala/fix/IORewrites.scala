package fix

import cats.effect.IO

object IORewrites {
  IO.interruptibleMany(IO.unit)

  IO.interruptible(IO.unit)
}
