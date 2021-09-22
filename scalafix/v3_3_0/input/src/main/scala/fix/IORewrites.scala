/*
rule = "scala:fix.v3_3_0"
 */
package fix

import cats.effect.IO

object IORewrites {
  IO.interruptible(false)(IO.unit)

  IO.interruptible(true)(IO.unit)
}
