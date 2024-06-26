package cats.effect.unsafe

import cats.effect.BaseSpec

class IORuntimeSpec extends BaseSpec {

  "IORuntimeSpec" should {
    "cleanup allRuntimes collection on shutdown" in {
      val (defaultScheduler, closeScheduler) = Scheduler.createDefaultScheduler()

      val runtime = IORuntime(null, null, defaultScheduler, closeScheduler, IORuntimeConfig())

      IORuntime.allRuntimes.unsafeHashtable().find(_ == runtime) must beEqualTo(Some(runtime))

      val _ = runtime.shutdown()

      IORuntime.allRuntimes.unsafeHashtable().find(_ == runtime) must beEqualTo(None)
    }

  }

}
