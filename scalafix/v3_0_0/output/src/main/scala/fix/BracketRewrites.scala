package fix

import cats.effect.IO
import cats.effect.Sync
import cats.effect.MonadCancel

object BracketRewrites {
  MonadCancel.apply[IO, Throwable]

  MonadCancel[IO, Throwable].guarantee(IO.unit, IO.unit)

  Sync[IO].guarantee(IO.unit, IO.unit)

  Sync[IO].guarantee( /* comment */ IO.unit, IO.unit)

  Sync[IO].guarantee(Sync[IO].guarantee(IO.unit, IO.unit), IO.unit)

  Sync[IO].guarantee(IO.defer(Sync[IO].guarantee(IO.unit, IO.unit)), IO.unit)

  def f1[F[_], E](implicit F: MonadCancel[F, E]): Unit = ()

  private val x1 = MonadCancel[IO, Throwable]
  x1.guarantee(IO.unit, IO.unit)

  trait MySync[F[_]] extends MonadCancel[F, Throwable]

  MonadCancel[IO, Throwable].uncancelable(_ => IO.unit)

  MonadCancel[IO, Throwable].uncancelable(_ => Sync[IO].guarantee(IO.unit, IO.unit))
}
