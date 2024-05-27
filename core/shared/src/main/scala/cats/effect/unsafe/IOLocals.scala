/*
 * Copyright 2020-2024 Typelevel
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

import IOLocalsConstants.ioLocalPropagation

object IOLocals {

  /**
   * `true` if IOLocal propagation is enabled
   */
  def arePropagating: Boolean = ioLocalPropagation

  def get[A](iol: IOLocal[A]): A = if (ioLocalPropagation) {
    val fiber = IOFiber.currentIOFiber()
    val state = if (fiber ne null) fiber.getLocalState() else IOLocalState.empty
    iol.getOrDefault(state)
  } else iol.getOrDefault(IOLocalState.empty)

  def set[A](iol: IOLocal[A], value: A): Unit = if (ioLocalPropagation) {
    val fiber = IOFiber.currentIOFiber()
    if (fiber ne null) {
      fiber.setLocalState(iol.set(fiber.getLocalState(), value))
    }
  }

  def reset[A](iol: IOLocal[A]): Unit = if (ioLocalPropagation) {
    val fiber = IOFiber.currentIOFiber()
    if (fiber ne null) {
      fiber.setLocalState(iol.reset(fiber.getLocalState()))
    }
  }

  def update[A](iol: IOLocal[A])(f: A => A): Unit = if (ioLocalPropagation) {
    val fiber = IOFiber.currentIOFiber()
    if (fiber ne null) {
      val state = fiber.getLocalState()
      fiber.setLocalState(iol.set(state, f(iol.getOrDefault(state))))
    }
  }

  def modify[A, B](iol: IOLocal[A])(f: A => (A, B)): B = if (ioLocalPropagation) {
    val fiber = IOFiber.currentIOFiber()
    if (fiber ne null) {
      val state = fiber.getLocalState()
      val (a2, b) = f(iol.getOrDefault(state))
      fiber.setLocalState(iol.set(state, a2))
      b
    } else f(iol.getOrDefault(IOLocalState.empty))._2
  } else f(iol.getOrDefault(IOLocalState.empty))._2

  def getAndSet[A](iol: IOLocal[A], a: A): A = if (ioLocalPropagation) {
    val fiber = IOFiber.currentIOFiber()
    if (fiber ne null) {
      val state = fiber.getLocalState()
      fiber.setLocalState(iol.set(state, a))
      iol.getOrDefault(state)
    } else iol.getOrDefault(IOLocalState.empty)
  } else iol.getOrDefault(IOLocalState.empty)

  def getAndReset[A](iol: IOLocal[A]): A = if (ioLocalPropagation) {
    val fiber = IOFiber.currentIOFiber()
    if (fiber ne null) {
      val state = fiber.getLocalState()
      fiber.setLocalState(iol.reset(state))
      iol.getOrDefault(state)
    } else iol.getOrDefault(IOLocalState.empty)
  } else iol.getOrDefault(IOLocalState.empty)

  private[effect] def getState = {
    val fiber = IOFiber.currentIOFiber()
    if (fiber ne null) fiber.getLocalState() else IOLocalState.empty
  }

  private[effect] def setState(state: IOLocalState) = {
    val fiber = IOFiber.currentIOFiber()
    if (fiber ne null) fiber.setLocalState(state)
  }

  // private[effect] def getAndClearState() = {
  //   val thread = Thread.currentThread()
  //   if (thread.isInstanceOf[WorkerThread[_]]) {
  //     val worker = thread.asInstanceOf[WorkerThread[_]]
  //     val state = worker.ioLocalState
  //     worker.ioLocalState = IOLocalState.empty
  //     state
  //   } else {
  //     val state = threadLocal.get()
  //     threadLocal.set(IOLocalState.empty)
  //     state
  //   }
  // }
}
