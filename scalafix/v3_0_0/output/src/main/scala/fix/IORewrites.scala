package fix

import cats.effect.IO

object IORewrites {
  IO.defer(IO.unit)

  IO.defer(IO.defer(IO.pure(0)))
}
