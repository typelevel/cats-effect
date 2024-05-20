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
sealed trait IOLocal[A] { self =>

  /**
   * Returns the current value.
   */
  def get: IO[A]

  /**
   * Sets the current value to `value`.
   */
  def set(value: A): IO[Unit]

  /**
   * Replaces the current value with the initial value.
   */
  def reset: IO[Unit]

  /**
   * Modifies the current value using the given update function.
   */
  def update(f: A => A): IO[Unit]

  /**
   * Like [[update]] but allows the update function to return an output value of type `B`.
   *
   * @see
   *   [[update]]
   */
  def modify[B](f: A => (A, B)): IO[B]

  /**
   * Replaces the current value with `value`, returning the previous value.
   *
   * The combination of [[get]] and [[set]].
   *
   * @see
   *   [[get]]
   * @see
   *   [[set]]
   */
  def getAndSet(value: A): IO[A]

  /**
   * Replaces the current value with the initial value, returning the previous value.
   *
   * The combination of [[get]] and [[reset]].
   *
   * @see
   *   [[get]]
   * @see
   *   [[reset]]
   */
  def getAndReset: IO[A]

  /**
   * Creates a lens to a value of some type `B` from current value and two functions: getter and
   * setter.
   *
   * All changes to the original value will be visible via lens getter and all changes applied
   * to 'refracted' value will be forwarded to the original via setter.
   *
   * Note that [[.set]] method requires special mention: while from the `IOLocal[B]` point of
   * view old value will be replaced with a new one, from `IOLocal[A]` POV old value will be
   * updated via setter. This means that for 'refracted' `IOLocal[B]` use of `set(b)` is
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
  final def lens[B](get: A => B)(set: A => B => A): IOLocal[B] = {
    import IOLocal.IOLocalLens

    self match {
      case lens: IOLocalLens[aa, A] =>
        // We process already created lens separately so
        // we wont pay additional `.get.flatMap` price for every call of
        // `set`, `update` or `modify` of resulting lens.
        // After all, our getters and setters are pure,
        // so `AndThen` allows us to safely compose them and
        // proxy calls to the 'original' `IOLocal` independent of
        // current nesting level.

        val getter = lens.getter.andThen(get)
        val setter = lens.setter.compose((p: (aa, B)) => (p._1, set(lens.getter(p._1))(p._2)))
        new IOLocalLens(lens.underlying, getter, setter)
      case _ =>
        val getter = AndThen(get)
        val setter = AndThen((p: (A, B)) => set(p._1)(p._2))
        new IOLocalLens(self, getter, setter)
    }
  }

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

  private[IOLocal] final class IOLocalImpl[A](default: A) extends IOLocal[A] { self =>
    private[this] def getOrDefault(state: IOLocalState): A =
      state.getOrElse(self, default).asInstanceOf[A]

    def get: IO[A] =
      IO.Local(state => (state, getOrDefault(state)))

    def set(value: A): IO[Unit] =
      IO.Local(state => (state.updated(self, value), ()))

    def reset: IO[Unit] =
      IO.Local(state => (state - self, ()))

    def update(f: A => A): IO[Unit] =
      IO.Local(state => (state.updated(self, f(getOrDefault(state))), ()))

    def modify[B](f: A => (A, B)): IO[B] =
      IO.Local { state =>
        val (a2, b) = f(getOrDefault(state))
        (state.updated(self, a2), b)
      }

    def getAndSet(value: A): IO[A] =
      IO.Local(state => (state.updated(self, value), getOrDefault(state)))

    def getAndReset: IO[A] =
      IO.Local(state => (state - self, getOrDefault(state)))
  }

  private[IOLocal] final class IOLocalLens[S, A](
      val underlying: IOLocal[S],
      val getter: AndThen[S, A],
      val setter: AndThen[(S, A), S])
      extends IOLocal[A] {
    def get: IO[A] =
      underlying.get.map(getter(_))

    def set(value: A): IO[Unit] =
      underlying.get.flatMap(s => underlying.set(setter(s -> value)))

    def reset: IO[Unit] =
      underlying.reset

    def update(f: A => A): IO[Unit] =
      underlying.get.flatMap(s => underlying.set(setter(s -> f(getter(s)))))

    def modify[B](f: A => (A, B)): IO[B] =
      underlying.get.flatMap { s =>
        val (a2, b) = f(getter(s))
        underlying.set(setter(s -> a2)).as(b)
      }

    def getAndSet(value: A): IO[A] =
      underlying.get.flatMap(s => underlying.set(setter(s -> value)).as(getter(s)))

    def getAndReset: IO[A] =
      underlying.get.flatMap(s => underlying.reset.as(getter(s)))
  }

}
