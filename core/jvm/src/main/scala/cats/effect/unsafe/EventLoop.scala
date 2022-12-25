/*
 * Copyright 2020-2022 Typelevel
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
package unsafe

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.reflect.ClassTag

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

trait EventLoop[+Registrar] extends ExecutionContext {

  protected def registrarTag: ClassTag[_ <: Registrar]

  def registrar(): Registrar

}

object EventLoop {
  def unapply[R](loop: EventLoop[Any])(ct: ClassTag[R]): Option[EventLoop[R]] =
    if (ct.runtimeClass.isAssignableFrom(loop.registrarTag.runtimeClass))
      Some(loop.asInstanceOf[EventLoop[R]])
    else
      None

  def fromPollingSystem(
      name: String,
      system: PollingSystem): (EventLoop[system.Poller], () => Unit) = {

    val done = new AtomicBoolean(false)
    val poller = system.makePoller()

    val loop = new Thread(name) with EventLoop[system.Poller] with ExecutionContextExecutor {

      val queue = new LinkedBlockingQueue[Runnable]

      def registrarTag: ClassTag[_ <: system.Poller] = system.pollerTag

      def registrar(): system.Poller = poller

      def execute(command: Runnable): Unit = {
        queue.put(command)
        poller.interrupt(this)
      }

      def reportFailure(cause: Throwable): Unit = cause.printStackTrace()

      override def run(): Unit = {
        while (!done.get()) {
          while (!queue.isEmpty()) queue.poll().run()
          poller.poll(-1)
        }
      }
    }

    val cleanup = () => {
      done.set(true)
      poller.interrupt(loop)
    }

    (loop, cleanup)
  }
}
