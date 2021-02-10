package fix

import cats.effect.IO
import cats.effect.MonadCancel
import cats.effect.Sync

object BracketRewrites {
  MonadCancel.apply[IO, Throwable]

  MonadCancel[IO, Throwable].guarantee(IO.unit, IO.unit)

  Sync[IO].guarantee(IO.unit, IO.unit)

  def f1[F[_], E](implicit F: MonadCancel[F, E]): Unit = ()
}
