package fix

import cats.effect.SyncIO

object SyncIORewrites {
  SyncIO.defer(SyncIO.unit)
  cats.effect.SyncIO.defer(SyncIO.unit)
}
