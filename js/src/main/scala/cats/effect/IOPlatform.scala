package cats
package effect

import scala.concurrent.duration.Duration

private[effect] object IOPlatform {

  def unsafeResync[A](ioa: IO[A], limit: Duration): A =
    throw new UnsupportedOperationException("cannot synchronously await result on JavaScript; use runAsync or unsafeRunAsync")
}
