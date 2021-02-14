/*
rule = "scala:fix.v3_0_0"
 */
package fix

import cats.effect.Async
import cats.effect.IO

object IORewrites {
  IO.suspend(IO.unit)

  IO.suspend(IO.suspend(IO.pure(0)))

  IO.async((_: Any) => ())

  IO.suspend(IO.async((_: Any) => ()))

  Async[IO].async((_: Any) => ())

  Async[IO].suspend(IO.unit)
}
