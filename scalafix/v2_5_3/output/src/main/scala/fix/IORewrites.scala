package fix

import cats.effect.IO

object IORewrites {
  IO.defer(IO.unit)
  cats.effect.IO.defer(IO.unit)
}
