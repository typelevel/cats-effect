/*
rule = "scala:fix.v3_0_0"
 */
package fix

import cats.effect.Async
import cats.effect.Concurrent
import cats.effect.IO
import cats.effect.Timer
import scala.concurrent.duration._

object IORewrites {
  IO.suspend(IO.unit)

  IO.suspend(IO.suspend(IO.pure(0)))

  IO.async((_: Any) => ())

  IO.suspend(IO.async((_: Any) => ()))

  Async[IO].async((_: Any) => ())

  Async[IO].suspend(IO.unit)

  def f1(implicit ev: Concurrent[IO]): IO[Unit] = IO.unit

  def f2(implicit timer: Timer[IO]): IO[Unit] = timer.sleep(1.second)
}
