/*
 * Copyright 2020-2023 Typelevel
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

  def get[A](iol: IOLocal[A]): A = if (ioLocalPropagation) {
    val thread = Thread.currentThread()
    val state =
      if (thread.isInstanceOf[WorkerThread])
        thread.asInstanceOf[WorkerThread].ioLocalState
      else
        threadLocal.get
    iol.getOrDefault(state)
  } else iol.getOrDefault(IOLocalState.empty)

  def set[A](iol: IOLocal[A], value: A): Unit = if (ioLocalPropagation) {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread]) {
      val worker = thread.asInstanceOf[WorkerThread]
      worker.ioLocalState = iol.set(worker.ioLocalState, value)
    } else {
      threadLocal.set(iol.set(threadLocal.get(), value))
    }
  } else ()

  def reset[A](iol: IOLocal[A]): Unit = if (ioLocalPropagation) {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread]) {
      val worker = thread.asInstanceOf[WorkerThread]
      worker.ioLocalState = iol.reset(worker.ioLocalState)
    } else {
      threadLocal.set(iol.reset(threadLocal.get()))
    }
  } else ()

  def update[A](iol: IOLocal[A])(f: A => A): Unit = if (ioLocalPropagation) {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread]) {
      val worker = thread.asInstanceOf[WorkerThread]
      val state = worker.ioLocalState
      worker.ioLocalState = iol.set(state, f(iol.getOrDefault(state)))
    } else {
      val state = threadLocal.get()
      threadLocal.set(iol.set(state, f(iol.getOrDefault(state))))
    }
  } else ()

  def modify[A, B](iol: IOLocal[A])(f: A => (A, B)): B = if (ioLocalPropagation) {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread]) {
      val worker = thread.asInstanceOf[WorkerThread]
      val state = worker.ioLocalState
      val (a2, b) = f(iol.getOrDefault(state))
      worker.ioLocalState = iol.set(state, a2)
      b
    } else {
      val state = threadLocal.get()
      val (a2, b) = f(iol.getOrDefault(state))
      threadLocal.set(iol.set(state, a2))
      b
    }
  } else f(iol.getOrDefault(IOLocalState.empty))._2

  def getAndSet[A](iol: IOLocal[A], a: A): A = if (ioLocalPropagation) {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread]) {
      val worker = thread.asInstanceOf[WorkerThread]
      val state = worker.ioLocalState
      worker.ioLocalState = iol.set(state, a)
      iol.getOrDefault(state)
    } else {
      val state = threadLocal.get()
      threadLocal.set(iol.set(state, a))
      iol.getOrDefault(state)
    }
  } else iol.getOrDefault(IOLocalState.empty)

  def getAndReset[A](iol: IOLocal[A]): A = if (ioLocalPropagation) {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread]) {
      val worker = thread.asInstanceOf[WorkerThread]
      val state = worker.ioLocalState
      worker.ioLocalState = iol.reset(state)
      iol.getOrDefault(state)
    } else {
      val state = threadLocal.get()
      threadLocal.set(iol.reset(state))
      iol.getOrDefault(state)
    }
  } else iol.getOrDefault(IOLocalState.empty)

  private[this] val threadLocal = new ThreadLocal[IOLocalState] {
    override def initialValue() = IOLocalState.empty
  }

  private[effect] def getState = {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread])
      thread.asInstanceOf[WorkerThread].ioLocalState
    else
      threadLocal.get()
  }

  private[effect] def setState(state: IOLocalState) = {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread])
      thread.asInstanceOf[WorkerThread].ioLocalState = state
    else
      threadLocal.set(state)
  }

  private[effect] def getAndClearState() = {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread]) {
      val worker = thread.asInstanceOf[WorkerThread]
      val state = worker.ioLocalState
      worker.ioLocalState = IOLocalState.empty
      state
    } else {
      val state = threadLocal.get()
      threadLocal.set(IOLocalState.empty)
      state
    }
  }
}
