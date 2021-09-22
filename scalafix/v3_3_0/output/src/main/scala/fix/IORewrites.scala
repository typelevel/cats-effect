package fix

import cats.effect.IO

object IORewrites {
  IO.interruptible(IO.unit)

  IO.interruptibleMany(IO.unit)
}
