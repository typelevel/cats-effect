package cats
package effect

import scala.concurrent.duration.Duration

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

private[effect] object IOPlatform {

  def unsafeResync[A](ioa: IO[A], limit: Duration): A = {
    val latch = new CountDownLatch(1)
    val ref = new AtomicReference[Attempt[A]](null)

    ioa unsafeRunAsync { a =>
      ref.set(a)
      latch.countDown()
    }

    if (limit == Duration.Inf)
      latch.await()
    else
      latch.await(limit.toMillis, TimeUnit.MILLISECONDS)

    ref.get().fold(throw _, a => a)
  }
}
