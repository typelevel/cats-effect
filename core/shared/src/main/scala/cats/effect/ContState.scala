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

import cats.effect.unsafe.WeakBag

import java.util.concurrent.atomic.AtomicReference

import Platform.static

/**
 * Possible states (held in the `AtomicReference`):
 *   - "initial": `get() == null`
 *   - "waiting": `get() == waiting` (sentinel)
 *   - "result": `get()` is the result from the callback
 *
 * Note: A `null` result is forbidden, so "initial" is always different from "result". Also,
 * `waitingSentinel` is private, so that can also be differentiated from a result (by object
 * identity).
 *
 * `wasFinalizing` and `handle` are published in terms of the `suspended` atomic variable in
 * `IOFiber`
 */
private final class ContState(var wasFinalizing: Boolean)
    extends AtomicReference[Either[Throwable, Any]] {

  val waiting: Either[Throwable, Any] =
    ContState.waitingSentinel // this is just a reference to the sentinel

  var handle: WeakBag.Handle = _
}

private object ContState {

  /**
   * This is a sentinel, signifying a "waiting" state of `ContState`; only its identity is
   * important. It must be private (so that no user code can access it), and it mustn't be used
   * for any other purpose.
   */
  @static private val waitingSentinel: Either[Throwable, Any] =
    new Right(null)
}
