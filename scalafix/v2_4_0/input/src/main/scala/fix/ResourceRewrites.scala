/*
rule = "scala:fix.v2_4_0"
 */
package fix

import cats.effect.IO
import cats.effect.Resource

object ResourceRewrites {
  Resource.liftF(IO.unit)
  cats.effect.Resource.liftF(IO.unit)
}
