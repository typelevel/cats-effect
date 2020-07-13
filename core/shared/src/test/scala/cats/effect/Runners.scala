/*
 * Copyright 2020 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect

import org.specs2.execute.AsResult
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.core.Execution

import scala.concurrent.{Await, Future, Promise, TimeoutException}
import scala.concurrent.duration._

trait Runners extends SpecificationLike with RunnersPlatform {

  def real[A: AsResult](test: => IO[A]): Execution =
    Execution.withEnvAsync(_ => timeout(test.unsafeToFuture(runtime()), 10.seconds))

  private def timeout[A](f: Future[A], duration: FiniteDuration): Future[A] = {
    val p = Promise[A]()
    val r = runtime()
    implicit val ec = r.compute

    val cancel = r.scheduler.sleep(duration, { () =>
      p.tryFailure(new TimeoutException)
    })

    f onComplete { result =>
      p.tryComplete(result)
      cancel.run()
    }

    p.future
  }
}
