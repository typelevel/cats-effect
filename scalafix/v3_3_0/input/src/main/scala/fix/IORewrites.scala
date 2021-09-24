/*
rule = "scala:fix.v3_3_0"
 */
package fix

import cats.effect.IO

object IORewrites {
  IO.interruptible(true)(IO.unit)

  IO.interruptible(false)(IO.unit)
}
