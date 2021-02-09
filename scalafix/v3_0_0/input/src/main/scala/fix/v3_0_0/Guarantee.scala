/*
rule = "scala:fix.v3_0_0"
 */
package fix.v3_0_0

import cats.effect.IO
import cats.effect.Bracket

class Guarantee {
  Bracket[IO, Throwable].guarantee(IO.unit)(IO.unit)
}
