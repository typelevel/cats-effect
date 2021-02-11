/*
rule = "scala:fix.v3_0_0"
 */
package fix

import cats.effect.concurrent.{ Deferred, Ref, Semaphore }

object PkgRewrites {
  locally(Deferred)
  locally(cats.effect.concurrent.Deferred)

  locally(Ref)
  locally(cats.effect.concurrent.Ref)

  locally(Semaphore)
  locally(cats.effect.concurrent.Semaphore)
}
