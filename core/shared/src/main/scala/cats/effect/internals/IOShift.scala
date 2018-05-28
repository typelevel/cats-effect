package cats.effect.internals

import cats.effect.IO

import scala.concurrent.ExecutionContext

private[effect] object IOShift {
  /** Implementation for `IO.shift`. */
  def apply(ec: ExecutionContext): IO[Unit] =
    IO.Async(new IOForkedStart[Unit] {
      def apply(conn: IOConnection, cb: Callback.T[Unit]): Unit =
        ec.execute(new Tick(cb))
    })

  private[internals] final class Tick(cb: Either[Throwable, Unit] => Unit)
    extends Runnable {
    def run() = cb(Callback.rightUnit)
  }
}
