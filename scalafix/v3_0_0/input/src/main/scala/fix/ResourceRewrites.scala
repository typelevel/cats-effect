/*
rule = "scala:fix.v3_0_0"
 */
package fix

import cats.effect.IO
import cats.effect.Resource

object ResourceRewrites {
  Resource.liftF(IO.unit)

  // TODO: Resource#parZip -> Resource#both when 3.0.0-M6 is released
}
