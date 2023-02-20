package cats.effect
package unsafe

import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.duration._

final class WstpSpec {

  val ctr = new AtomicLong

  val wstp = new WorkStealingThreadPool(
    threadCount = 2,
    threadPrefix = "testWorker",
    blockerThreadPrefix = "testBlocker",
    runtimeBlockingExpiration = 3.seconds,
    reportFailure0 = _.printStackTrace(),
  )

  val runtime = IORuntime
    .builder()
    .setCompute(wstp, () => wstp.shutdown())
    .setConfig(IORuntimeConfig(cancelationCheckThreshold = 1, autoYieldThreshold = 2))
    .build()

  def go(): Unit = {
    val tsk1 = IO { ctr.incrementAndGet() }.foreverM
    val fib1 = tsk1.unsafeRunFiber((), _ => (), null)(runtime)
    for (_ <- 1 to 10) {
      val tsk2 = IO.blocking { Thread.sleep(5L) }
      tsk2.unsafeRunFiber((), _ => (), _ => ())(runtime)
    }
    fib1.join.unsafeRunFiber((), _ => (), _ => ())(runtime)
    var c = 0
    while (c < 5000000) {
      Thread.sleep(1000L)
      println(ctr.get())
      c += 1
    }
    ()
  }
}

object WstpSpec {
  def main(args: Array[String]): Unit = {
    println("Hello!")
    val spec = new WstpSpec()
    spec.go()
    println("Done.")
  }
}
