/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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
package internals

import java.util.{Collection, Collections, List}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{
  Callable,
  Future,
  RejectedExecutionException,
  ScheduledExecutorService,
  ScheduledFuture,
  TimeUnit
}
import scala.concurrent.ExecutionContext

private[effect] trait IOAppCompanionPlatform {

  /**
   * Supports customization of the execution context and scheduler
   * used by an [[IOApp]]
   */
  trait WithContext extends IOApp {

    /**
     * Provides an execution context for this app to use as its main
     * thread pool.  This execution context is used by the implicit
     * [[contextShift]] and [[timer]] instances.
     *
     * [[main]] will block until this resource is released.  To
     * implement a graceful shutdown where all pending work is
     * complete, call `awaitTermination` in the release:
     *
     * {{{
     * Resource.make(SyncIO(Executors.newFixedThreadPool(8)))(pool => SyncIO{
     *   pool.shutdown()
     *   pool.awaitTermination(10, TimeUnit.SECONDS)
     * }).map(ExecutionContext.fromExecutorService)
     * }}}
     */
    protected def executionContextResource: Resource[SyncIO, ExecutionContext]

    /**
     * Provides a scheduler for this app to use.  This scheduler is
     * used by the implicit [[timer]] instance.
     *
     * [[main]] will block until this resource is released.  To
     * implement a graceful shutdown where all pending scheduled
     * actions are triggered, call `awaitTermination` in the release:
     *
     * {{{
     * Resource.make(SyncIO(Executors.newScheduledThreadPool(2)))(pool => SyncIO{
     *   pool.shutdown
     *   pool.awaitTermination(10, TimeUnit.SECONDS)
     * })
     * }}}
     *
     * The default implementation uses an internal scheduler built on
     * two daemon threads, which can't be shut down or awaited.  In
     * this default implementation, any pending actions are dropped
     * when `run` completes.
     */
    protected def schedulerResource: Resource[SyncIO, ScheduledExecutorService] =
      defaultScheduler

    /**
     * The main execution context for this app, provided by
     * [[executionContextResource]].  Outside `run`, this context will
     * reject all tasks.
     */
    final protected def executionContext: ExecutionContext =
      currentContext.get().executionContext

    /**
     * The main scheduler for this app, provided by
     * [[schedulerResource]].  Outside `run`, this scheduler will
     * reject all tasks.
     */
    final protected def scheduler: ScheduledExecutorService =
      currentContext.get().scheduler

    /**
     * The default [[ContextShift]] for this app.  Based on
     * [[executionContext]].
     */
    implicit final override protected def contextShift: ContextShift[IO] =
      currentContext.get().contextShift

    /**
     * The default [[Timer]] for this app.  Based on [[executionContext]]
     * and [[scheduler]].
     */
    implicit final override protected def timer: Timer[IO] =
      currentContext.get().timer

    /**
     * Uses [[executionContextResource]] and [[schedulerResource]] to
     * execute `run` and exit with its result.  The main thread is
     * blocked until both resources are fully released, permitting a
     * graceful shutdown of the application.
     */
    final override def main(args: Array[String]): Unit = synchronized {
      val mainIO = executionContextResource.use { ec =>
        schedulerResource.use { sc =>
          val init = SyncIO {
            if (!currentContext.compareAndSet(ClosedContext, new Context(ec, sc))) {
              throw new IllegalStateException("IOApp already in use!")
            }
          }
          val shutdown = SyncIO(currentContext.set(ClosedContext))
          init.bracket(_ => SyncIO(super.main(args)))(_ => shutdown)
        }
      }
      mainIO.unsafeRunSync()
    }
  }

  private[this] val defaultScheduler: Resource[SyncIO, ScheduledExecutorService] =
    Resource.liftF(SyncIO(IOTimer.scheduler))

  final private class Context(val executionContext: ExecutionContext, val scheduler: ScheduledExecutorService) {
    val timer: Timer[IO] = IO.timer(executionContext, scheduler)
    val contextShift: ContextShift[IO] = IO.contextShift(executionContext)
  }

  private[this] val ClosedExecutionContext: ExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit =
      reject()

    def reportFailure(cause: Throwable): Unit =
      Logger.reportFailure(cause)
  }

  private[this] val ClosedScheduler: ScheduledExecutorService = new ScheduledExecutorService {
    def schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture[_] =
      reject()

    def schedule[V](callable: Callable[V], delay: Long, unit: TimeUnit): ScheduledFuture[V] =
      reject()

    def scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture[_] =
      reject()

    def scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture[_] =
      reject()

    def shutdown(): Unit =
      ()

    def shutdownNow(): List[Runnable] =
      Collections.emptyList[Runnable]

    def isShutdown: Boolean =
      true

    def isTerminated: Boolean =
      true

    def awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
      true

    def submit[T](task: Callable[T]): Future[T] =
      reject()

    def submit[T](task: Runnable, result: T): Future[T] =
      reject()

    def submit(task: Runnable): Future[_] =
      reject()

    def invokeAll[T](tasks: Collection[_ <: Callable[T]]): List[Future[T]] =
      reject()

    def invokeAll[T](tasks: Collection[_ <: Callable[T]], timeout: Long, unit: TimeUnit): List[Future[T]] =
      reject()

    def invokeAny[T](tasks: Collection[_ <: Callable[T]]): T =
      reject()

    def invokeAny[T](tasks: Collection[_ <: Callable[T]], timeout: Long, unit: TimeUnit): T =
      reject()

    def execute(command: Runnable): Unit =
      reject()
  }

  private[this] val ClosedContext = new Context(ClosedExecutionContext, ClosedScheduler)

  private[this] val currentContext = new AtomicReference(ClosedContext)

  private def reject(): Nothing =
    throw new RejectedExecutionException("IOApp and its thread pools are shutdown")
}
