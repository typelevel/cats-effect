/*
 * Copyright 2020-2021 Typelevel
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

package cats.effect.unsafe
package metrics

/**
 * An MBean interface for monitoring the [[IO]] work stealing compute pool.
 */
trait ComputePoolSamplerMBean {

  /**
   * Returns the number of worker threads in the compute pool.
   *
   * @return the number of worker threads in the compute pool
   */
  def getWorkerThreadCount: Int

  /**
   * Returns the number of active worker threads currently executing fibers.
   *
   * @return the number of currently active worker threads
   */
  def getActiveThreadCount: Int

  /**
   * Returns the number of worker threads searching for fibers to steal from
   * other worker threads.
   *
   * @return the number of worker threads searching for work
   */
  def getSearchingThreadCount: Int
}
