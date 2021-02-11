/*
rule = "scala:fix.v3_0_0"
 */
package fix

import cats.effect.IO

object IORewrites {
  IO.suspend(IO.unit)

  IO.suspend(IO.suspend(IO.pure(0)))
}
