/*
rule = "scala:fix.v2_5_3"
 */
package fix

import cats.effect.SyncIO

object SyncIORewrites {
  SyncIO.suspend(SyncIO.unit)
  cats.effect.SyncIO.suspend(SyncIO.unit)
}
