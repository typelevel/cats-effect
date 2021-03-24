package fix

import cats.effect.Async
import cats.effect.Concurrent
import cats.effect.IO
import scala.concurrent.duration._
import cats.effect.Temporal

object IORewrites {
  IO.defer(IO.unit)

  IO.defer(IO.defer(IO.pure(0)))

  IO.async_((_: Any) => ())

  IO.defer(IO.async_((_: Any) => ()))

  Async[IO].async_((_: Any) => ())

  Async[IO].defer(IO.unit)

  def f1: IO[Unit] = IO.unit

  def f2(implicit timer: Temporal[IO]): IO[Unit] = timer.sleep(1.second)
}
