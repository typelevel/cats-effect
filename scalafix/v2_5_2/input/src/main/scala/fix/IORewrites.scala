/*
rule = "scala:fix.v2_5_3"
 */
package fix

import cats.effect.IO

object IORewrites {
  IO.suspend(IO.unit)
  cats.effect.IO.suspend(IO.unit)
}
