package fix.v3_0_0

import cats.effect.IO
import cats.effect.MonadCancel
import cats.effect.Sync

class Guarantee {
  MonadCancel[IO, Throwable].guarantee(IO.unit, IO.unit)
  Sync[IO].guarantee(IO.unit, IO.unit)
}
