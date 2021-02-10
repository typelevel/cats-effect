package fix

import cats.effect.IO
import cats.effect.MonadCancel
import cats.effect.Sync

object BracketRewrites {
  MonadCancel[IO, Throwable].guarantee(IO.unit, IO.unit)
  Sync[IO].guarantee(IO.unit, IO.unit)
}
