/*
rule = "scala:fix.v3_0_0"
 */
package fix

import cats.effect.IO
import cats.effect.Bracket
import cats.effect.Sync

object BracketRewrites {
  Bracket[IO, Throwable].guarantee(IO.unit)(IO.unit)
  Sync[IO].guarantee(IO.unit)(IO.unit)
}
