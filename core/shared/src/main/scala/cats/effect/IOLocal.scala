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

import cats.data.AndThen

/**
 * [[IOLocal]] provides a handy way of manipulating a context on different scopes.
 *
 * In some scenarios, [[IOLocal]] can be considered as an alternative to [[cats.data.Kleisli]].
 *
 * [[IOLocal]] should not be treated as [[cats.effect.kernel.Ref Ref]], since the former abides
 * different laws.
 *
 * Once a fiber is forked, for example by `Spawn[F].start`, the forked fiber manipulates the
 * copy of the parent's context. For example, two forked fibers will never see each other's
 * modifications to the same [[IOLocal]], each fiber will only see its own modifications.
 *
 * ===Operations on [[IOLocal]] are visible to the fiber===
 *
 * {{{
 * ┌────────────┐               ┌────────────┐               ┌────────────┐
 * │  Fiber A   │ update(_ + 1) │  Fiber A   │ update(_ + 1) │  Fiber A   │
 * │ (local 42) │──────────────►│ (local 43) │──────────────►│ (local 44) │
 * └────────────┘               └────────────┘               └────────────┘
 * }}}
 *
 * {{{
 *  def inc(name: String, local: IOLocal[Int]): IO[Unit] =
 *    local.update(_ + 1) >> local.get.flatMap(current => IO.println(s"fiber $$name: $$current"))
 *
 *  for {
 *    local   <- IOLocal(42)
 *    _       <- inc(1, local)
 *    _       <- inc(2, local)
 *    current <- local.get
 *    _       <- IO.println(s"fiber A: $$current")
 *  } yield ()
 *
 *  // output:
 *  // update 1: 43
 *  // update 2: 44
 *  // fiber A: 44
 * }}}
 *
 * ===A forked fiber operates on a copy of the parent [[IOLocal]]===
 *
 * A '''forked''' fiber (i.e. via `Spawn[F].start`) operates on a '''copy''' of the parent
 * `IOLocal`. Hence, the children operations are not reflected on the parent context.
 *
 * {{{
 *                       ┌────────────┐               ┌────────────┐
 *                  fork │  Fiber B   │ update(_ - 1) │  Fiber B   │
 *                ┌─────►│ (local 42) │──────────────►│ (local 41) │
 *                │      └────────────┘               └────────────┘
 * ┌────────────┐─┘                                   ┌────────────┐
 * │  Fiber A   │                                     │  Fiber A   │
 * │ (local 42) │────────────────────────────────────►│ (local 42) │
 * └────────────┘─┐                                   └────────────┘
 *                │      ┌────────────┐               ┌────────────┐
 *                │ fork │  Fiber C   │ update(_ + 1) │  Fiber C   │
 *                └─────►│ (local 42) │──────────────►│ (local 43) │
 *                       └────────────┘               └────────────┘
 * }}}
 *
 * {{{
 *  def update(name: String, local: IOLocal[Int], f: Int => Int): IO[Unit] =
 *    local.update(f) >> local.get.flatMap(current => IO.println(s"$$name: $$current"))
 *
 *  for {
 *    local   <- IOLocal(42)
 *    fiber1  <- update("fiber B", local, _ - 1).start
 *    fiber2  <- update("fiber C", local, _ + 1).start
 *    _       <- fiber1.joinWithNever
 *    _       <- fiber2.joinWithNever
 *    current <- local.get
 *    _       <- IO.println(s"fiber A: $$current")
 *  } yield ()
 *
 *  // output:
 *  // fiber B: 41
 *  // fiber C: 43
 *  // fiber A: 42
 * }}}
 *
 * ===Parent operations on [[IOLocal]] are invisible to children===
 *
 * {{{
 *                       ┌────────────┐               ┌────────────┐
 *                  fork │  Fiber B   │ update(_ + 1) │  Fiber B   │
 *                ┌─────►│ (local 42) │──────────────►│ (local 43) │
 *                │      └────────────┘               └────────────┘
 * ┌────────────┐─┘                                   ┌────────────┐
 * │  Fiber A   │        update(_ - 1)                │  Fiber A   │
 * │ (local 42) │────────────────────────────────────►│ (local 41) │
 * └────────────┘─┐                                   └────────────┘
 *                │      ┌────────────┐               ┌────────────┐
 *                │ fork │  Fiber C   │ update(_ + 2) │  Fiber C   │
 *                └─────►│ (local 42) │──────────────►│ (local 44) │
 *                       └────────────┘               └────────────┘
 * }}}
 *
 * {{{
 *  def update(name: String, local: IOLocal[Int], f: Int => Int): IO[Unit] =
 *    IO.sleep(1.second) >> local.update(f) >> local.get.flatMap(current => IO.println(s"$$name: $$current"))
 *
 *  for {
 *    local  <- IOLocal(42)
 *    fiber1 <- update("fiber B", local, _ + 1).start
 *    fiber2 <- update("fiber C", local, _ + 2).start
 *    _      <- fiber1.joinWithNever
 *    _      <- fiber2.joinWithNever
 *    _      <- update("fiber A", local, _ - 1)
 *  } yield ()
 *
 *  // output:
 *  // fiber B: 43
 *  // fiber C: 44
 *  // fiber A: 41
 * }}}
 *
 * @tparam A
 *   the type of the local value
 */
sealed trait IOLocal[A] extends IOLocalPlatform[A] { self =>

  protected[effect] def getOrDefault(state: IOLocalState): A

  protected[effect] def set(state: IOLocalState, a: A): IOLocalState

  protected[effect] def reset(state: IOLocalState): IOLocalState

  /**
   * Returns the current value.
   */
  final def get: IO[A] =
    IO.Local(state => (state, getOrDefault(state)))

  /**
   * Sets the current value to `value`.
   */
  final def set(value: A): IO[Unit] =
    IO.Local(state => (set(state, value), ()))

  /**
   * Replaces the current value with the initial value.
   */
  final def reset: IO[Unit] =
    IO.Local(state => (reset(state), ()))

  /**
   * Modifies the current value using the given update function.
   */
  final def update(f: A => A): IO[Unit] =
    IO.Local(state => (set(state, f(getOrDefault(state))), ()))

  /**
   * Like [[update]] but allows the update function to return an output value of type `B`.
   *
   * @see
   *   [[update]]
   */
  final def modify[B](f: A => (A, B)): IO[B] =
    IO.Local { state =>
      val (a2, b) = f(getOrDefault(state))
      (set(state, a2), b)
    }

  /**
   * Replaces the current value with `value`, returning the previous value.
   *
   * The combination of [[get]] and [[set(value:* set]].
   *
   * @see
   *   [[get]]
   * @see
   *   [[set(value:* set]]
   */
  final def getAndSet(value: A): IO[A] =
    IO.Local(state => (set(state, value), getOrDefault(state)))

  /**
   * Replaces the current value with the initial value, returning the previous value.
   *
   * The combination of [[get]] and [[reset:* reset]].
   *
   * @see
   *   [[get]]
   * @see
   *   [[reset:* reset]]
   */
  final def getAndReset: IO[A] =
    IO.Local(state => (reset(state), getOrDefault(state)))

  /**
   * Creates a lens to a value of some type `B` from current value and two functions: getter and
   * setter.
   *
   * All changes to the original value will be visible via lens getter and all changes applied
   * to 'refracted' value will be forwarded to the original via setter.
   *
   * Note that [[.set(value* set]] method requires special mention: while from the `IOLocal[B]`
   * point of view old value will be replaced with a new one, from `IOLocal[A]` POV old value
   * will be updated via setter. This means that for 'refracted' `IOLocal[B]` use of `set(b)` is
   * equivalent to `reset *> set(b)`, but it does not hold for original `IOLocal[A]`:
   *
   * {{{
   *   update(a => set(setter(a)(b)) =!= (reset *> update(default => set(setter(default)(b)))
   * }}}
   *
   * @example
   *
   * {{{
   *   for {
   *     base <- IOLocal(42 -> "empty")
   *     lens = base.lens(_._1) { case (_, s) => i => (i, s) }
   *     _    <- lens.update(_ + 1) // `42` is visible in lens closure
   *     _    <- base.get // returns `(43, "empty")`
   *     _    <- base.set(1 -> "some")
   *     _    <- lens.set(42)
   *     _    <- base.get // returns `(42, "some")`
   *   } yield ()
   * }}}
   */
  def lens[B](get: A => B)(set: A => B => A): IOLocal[B]

}

object IOLocal {

  /**
   * Creates a new instance of [[IOLocal]] with the given default value.
   *
   * The creation is effectful, because [[IOLocal]] models mutable state, and allocating mutable
   * state is not pure.
   *
   * @param default
   *   the default value
   * @tparam A
   *   the type of the local value
   */
  def apply[A](default: A): IO[IOLocal[A]] = IO(new IOLocalImpl(default))

  /**
   * `true` if IOLocal-Threadlocal propagation is enabled
   */
  def isPropagating: Boolean = IOFiberConstants.ioLocalPropagation

  private[effect] def getThreadLocalState() = {
    val fiber = IOFiber.currentIOFiber()
    if (fiber ne null) fiber.getLocalState() else IOLocalState.empty
  }

  private[effect] def setThreadLocalState(state: IOLocalState) = {
    val fiber = IOFiber.currentIOFiber()
    if (fiber ne null) fiber.setLocalState(state)
  }

  private final class IOLocalImpl[A](default: A) extends IOLocal[A] {

    def getOrDefault(state: IOLocalState): A =
      state.getOrElse(this, default).asInstanceOf[A]

    def set(state: IOLocalState, a: A): IOLocalState = state.updated(this, a)

    def reset(state: IOLocalState): IOLocalState = state - this

    def lens[B](get: A => B)(set: A => B => A): IOLocal[B] =
      new IOLocal.IOLocalLens(this, get, (ab: (A, B)) => set(ab._1)(ab._2))
  }

  private final class IOLocalLens[S, A](
      underlying: IOLocal[S],
      getter: S => A,
      setter: ((S, A)) => S)
      extends IOLocal[A] {

    def getOrDefault(state: IOLocalState): A =
      getter(underlying.getOrDefault(state))

    def set(state: IOLocalState, a: A): IOLocalState =
      underlying.set(state, setter((underlying.getOrDefault(state), a)))

    def reset(state: IOLocalState): IOLocalState = underlying.reset(state)

    def lens[B](get: A => B)(set: A => B => A): IOLocal[B] = {
      // We process already created lens separately so
      // we wont pay additional `.get.flatMap` price for every call of
      // `set`, `update` or `modify` of resulting lens.
      // After all, our getters and setters are pure,
      // so `AndThen` allows us to safely compose them and
      // proxy calls to the 'original' `IOLocal` independent of
      // current nesting level.

      val getter = AndThen(this.getter).andThen(get)
      val setter =
        AndThen(this.setter).compose((sb: (S, B)) => (sb._1, set(this.getter(sb._1))(sb._2)))
      new IOLocalLens(underlying, getter, setter)
    }
  }

}
