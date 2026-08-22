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

package cats.effect

private[effect] trait FiberSnapshotPlatform { self: FiberSnapshot =>

  /**
   * Mapping of worker threads to their currently active fibers.
   */
  def workers: Map[WorkerInfo, List[FiberInfo]]

}

private[effect] trait FiberSnapshotCompanionPlatform { self: FiberSnapshot.type =>

  private val Empty: FiberSnapshot = FiberSnapshotImpl(Nil, Map.empty)

  def apply(
      external: List[FiberInfo],
      workers: Map[WorkerInfo, List[FiberInfo]]
  ): FiberSnapshot =
    FiberSnapshotImpl(external, workers)

  def empty: FiberSnapshot = Empty

  private case class FiberSnapshotImpl(
      external: List[FiberInfo],
      workers: Map[WorkerInfo, List[FiberInfo]]
  ) extends FiberSnapshot

}
