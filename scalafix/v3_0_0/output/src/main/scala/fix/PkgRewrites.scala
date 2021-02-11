package fix

import cats.effect.{ Deferred, Ref }
import cats.effect.std.Semaphore

object PkgRewrites {
  locally(Deferred)
  locally(_root_.cats.effect.Deferred)

  locally(Ref)
  locally(_root_.cats.effect.Ref)

  locally(Semaphore)
  locally(cats.effect.std.Semaphore)
}
