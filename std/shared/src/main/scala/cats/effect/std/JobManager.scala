/*
 * Copyright 2020-2025 Typelevel
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

package cats.effect.std

import cats.effect.kernel._
import cats.syntax.all._

/**
 * A `JobManager` allows you to launch `Jobs` in the background using a unique identifier. Then
 * you can use the identifier to query for the status of the job, wait for its completion, or
 * cancel it.
 */
trait JobManager[F[_], Id, S, R] {

  /**
   * Creates and launches the given `Job` in the background. If another Job with the same id was
   * already running, it will be cancelled before starting this one.
   *
   * Note: This waits until the actual job's `run` process has started. In other words, you can
   * query the `status` of the associated id immediately after.
   *
   * @return
   *   the [[cats.effect.kernel.Fiber]] of the started `Job`.
   */
  def startJob(id: Id, job: Resource[F, JobManager.Job[F, S, R]]): F[Fiber[F, Throwable, R]]

  /**
   * Waits until the given `Job` finishes and returns its result.
   *
   * Note: If `id` does not exists or the `Job` already finished then the returned value will be
   * a `None`.
   */
  def join(id: Id): F[Option[Outcome[F, Throwable, R]]]

  /**
   * Gets the status of the `Job` associated with the given `id`.
   *
   * Note: If `id` does not exists or the `Job` already finished then the returned value will be
   * a `None`.
   */
  def getJobStatus(id: Id): F[Option[S]]

  /**
   * Signals cancellation of the `Job` associated with the given `id`, and waits for its
   * completion.
   */
  def cancelJob(id: Id): F[Unit]
}

object JobManager {

  /**
   * Represents a job managed by a `JobManager`.
   */
  trait Job[F[_], S, R] {

    /**
     * Starts the logic of this `Job`.
     */
    def run: F[R]

    /**
     * Gets the status of this `Job`.
     */
    def getStatus: F[S]
  }

  private final case class RunningJob[F[_], S, R](
      token: Unique.Token,
      status: F[S],
      fiber: Fiber[F, Throwable, R]
  )

  def apply[F[_], Id, S, R](
      implicit F: Concurrent[F]
  ): Resource[F, JobManager[F, Id, S, R]] =
    for {
      supervisor <- Supervisor[F](await = true)
      jobsMap <- Resource.eval(MapRef[F, Id, RunningJob[F, S, R]])
    } yield new JobManager[F, Id, S, R] {
      override def startJob(
          id: Id,
          jobR: Resource[F, Job[F, S, R]]
      ): F[Fiber[F, Throwable, R]] = {
        (
          F.unique,
          Deferred[F, Fiber[F, Throwable, R]]
        ).flatMapN {
          case (jobToken, waitForJobRegistration) =>
            // Running a job has the following steps:
            // 1. Run the job setup (Resource acquire).
            // 2. Launch the job in the background.
            // 3. Register the job in the jobsMap.
            // 4. In case there was another job already register with the same id, cancel it.
            // 5. Wait for the job to finish.
            // 6. Run the job cleanup (Resource release).
            val runJob = jobR.use { job =>
              supervisor
                .supervise(
                  // Ensure that no matter what happens with the job,
                  // we will clean up it from the jobsMap.
                  F.guarantee(
                    job.run,
                    fin = jobsMap(id).update { runningJob =>
                      // Guard to avoid removing a different job from the jobsMap.
                      runningJob.filterNot(_.token == jobToken)
                    }
                  )
                )
                .flatMap { jobFiber =>
                  val registerJob =
                    F.guarantee(
                      jobsMap(id).getAndSet(
                        RunningJob(
                          token = jobToken,
                          status = job.getStatus,
                          fiber = jobFiber
                        ).some
                      ),
                      fin = waitForJobRegistration.complete(jobFiber).void
                    )

                  registerJob.flatMap(_.traverse_(_.fiber.cancel)) >> jobFiber.join
                }
            }

            // Starts the run job process in the background,
            // but wait until it has been registered in the jobsMap.
            supervisor.supervise(runJob) >> waitForJobRegistration.get
        }
      }

      override def join(id: Id): F[Option[Outcome[F, Throwable, R]]] =
        jobsMap(id).get.flatMap(_.traverse(_.fiber.join))

      override def getJobStatus(id: Id): F[Option[S]] =
        jobsMap(id).get.flatMap(_.traverse(_.status))

      override def cancelJob(id: Id): F[Unit] =
        F.uncancelable(_ => jobsMap(id).get.flatMap(_.traverse_(_.fiber.cancel)))
    }
}
