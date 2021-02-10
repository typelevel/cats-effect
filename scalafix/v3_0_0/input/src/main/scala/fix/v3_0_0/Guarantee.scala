/*
rule = "scala:fix.v3_0_0"
 */
package fix.v3_0_0

import cats.effect.IO
import cats.effect.Bracket
import cats.effect.Sync

class Guarantee {
  Bracket[IO, Throwable].guarantee(IO.unit)(IO.unit)
  Sync[IO].guarantee(IO.unit)(IO.unit)
}
