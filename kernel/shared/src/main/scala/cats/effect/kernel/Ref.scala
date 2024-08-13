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

package cats
package effect
package kernel

import cats.data.State
import cats.effect.kernel.Ref.{TransformedRef, TransformedRef2}
import cats.syntax.all._

/**
 * A thread-safe, concurrent mutable reference.
 *
 * Provides safe concurrent access and modification of its content, but no functionality for
 * synchronisation, which is instead handled by [[Deferred]]. For this reason, a `Ref` is always
 * initialised to a value.
 *
 * The default implementation is nonblocking and lightweight, consisting essentially of a purely
 * functional wrapper over an `AtomicReference`. Consequently it ''must not'' be used to store
 * mutable data as `AtomicReference#compareAndSet` and friends are dependent upon object
 * reference equality.
 *
 * See also `cats.effect.std.AtomicCell` class from `cats-effect-std` for an alternative that
 * ensures exclusive access and effectual updates.
 *
 * If your contents are an immutable `Map[K, V]`, and all your operations are per-key, consider
 * using `cats.effect.std.MapRef`.
 */
abstract class Ref[F[_], A] extends RefSource[F, A] with RefSink[F, A] {

  /**
   * Updates the current value using `f` and returns the previous value.
   *
   * In case of retries caused by concurrent modifications, the returned value will be the last
   * one before a successful update.
   */
  def getAndUpdate(f: A => A): F[A] = modify { a => (f(a), a) }

  /**
   * Replaces the current value with `a`, returning the previous value.
   */
  def getAndSet(a: A): F[A] = getAndUpdate(_ => a)

  /**
   * Updates the current value using `f`, and returns the updated value.
   */
  def updateAndGet(f: A => A): F[A] =
    modify { a =>
      val newA = f(a)
      (newA, newA)
    }

  /**
   * Obtains a snapshot of the current value, and a setter for updating it.
   *
   * The setter attempts to modify the contents from the snapshot to the new value (and return
   * `true`). If it cannot do this (because the contents changed since taking the snapshot), the
   * setter is a noop and returns `false`.
   *
   * Satisfies: `r.access.map(_._1) == r.get` and `r.access.flatMap { case (v, setter) =>
   * setter(f(v)) } == r.tryUpdate(f).map(_.isDefined)`.
   */
  def access: F[(A, A => F[Boolean])]

  /**
   * Attempts to modify the current value once, returning `false` if another concurrent
   * modification completes between the time the variable is read and the time it is set.
   */
  def tryUpdate(f: A => A): F[Boolean]

  /**
   * Like `tryUpdate` but allows the update function to return an output value of type `B`. The
   * returned action completes with `None` if the value is not updated successfully and
   * `Some(b)` otherwise.
   */
  def tryModify[B](f: A => (A, B)): F[Option[B]]

  /**
   * Modifies the current value using the supplied update function. If another modification
   * occurs between the time the current value is read and subsequently updated, the
   * modification is retried using the new value. Hence, `f` may be invoked multiple times.
   *
   * Satisfies: `r.update(_ => a) == r.set(a)`
   */
  def update(f: A => A): F[Unit]

  /**
   * Like `tryModify` but retries until the update has been successfully made.
   */
  def modify[B](f: A => (A, B)): F[B]

  /**
   * Like [[modify]] but schedules resulting effect right after modification.
   *
   * Useful for implementing effectful transition of a state machine, in which an effect is
   * performed based on current state and the state must be updated to reflect that this effect
   * will be performed.
   *
   * Both modification and finalizer are within a single uncancelable region, to prevent
   * canceled finalizers from leaving the Ref's value permanently out of sync with effects
   * actually performed. if you need cancellation mechanic in finalizer please see
   * [[flatModifyFull]].
   *
   * @see
   *   [[modify]]
   * @see
   *   [[flatModifyFull]]
   */
  def flatModify[B](f: A => (A, F[B]))(implicit F: MonadCancel[F, _]): F[B] =
    F.uncancelable(_ => F.flatten(modify(f)))

  /**
   * Like [[modify]] but schedules resulting effect right after modification.
   *
   * Unlike [[flatModify]] finalizer cancellation could be unmasked via supplied `Poll`.
   * Modification itself is still uncancelable.
   *
   * When used as part of a state machine, cancelable regions should usually have an `onCancel`
   * finalizer to update the state to reflect that the effect will not be performed.
   *
   * @see
   *   [[modify]]
   * @see
   *   [[flatModify]]
   */
  def flatModifyFull[B](f: (Poll[F], A) => (A, F[B]))(implicit F: MonadCancel[F, _]): F[B] =
    F.uncancelable(poll => F.flatten(modify(f(poll, _))))

  /**
   * Update the value of this `Ref` with a state computation.
   *
   * The current value of this `Ref` is used as the initial state and the computed output state
   * is stored in this `Ref` after computation completes. If a concurrent modification occurs,
   * `None` is returned.
   */
  def tryModifyState[B](state: State[A, B]): F[Option[B]]

  /**
   * Like [[tryModifyState]] but retries the modification until successful.
   */
  def modifyState[B](state: State[A, B]): F[B]

  /**
   * Like [[modifyState]] but schedules resulting effect right after state computation & update.
   *
   * Both modification and finalizer are uncancelable, if you need cancellation mechanic in
   * finalizer please see [[flatModifyStateFull]].
   *
   * @see
   *   [[modifyState]]
   * @see
   *   [[flatModifyStateFull]]
   */
  def flatModifyState[B](state: State[A, F[B]])(implicit F: MonadCancel[F, _]): F[B] =
    F.uncancelable(_ => F.flatten(modifyState(state)))

  /**
   * Like [[modifyState]] but schedules resulting effect right after modification.
   *
   * Unlike [[flatModifyState]] finalizer cancellation could be masked via supplied `Poll[F]`.
   * Modification itself is still uncancelable.
   *
   * @see
   *   [[modifyState]]
   * @see
   *   [[flatModifyState]]
   */
  def flatModifyStateFull[B](state: Poll[F] => State[A, F[B]])(
      implicit F: MonadCancel[F, _]): F[B] =
    F.uncancelable(poll => F.flatten(modifyState(state(poll))))

  /**
   * Modify the context `F` using transformation `f`.
   */
  def mapK[G[_]](f: F ~> G)(implicit G: Functor[G], dummy: DummyImplicit): Ref[G, A] =
    new TransformedRef2(this, f)

  @deprecated("Use mapK with Functor[G] constraint", "3.6.0")
  def mapK[G[_]](f: F ~> G, F: Functor[F]): Ref[G, A] =
    new TransformedRef(this, f)(F)
}

object Ref {

  @annotation.implicitNotFound(
    "Cannot find an instance for Ref.Make. Add implicit evidence of Concurrent[${F}, _] or Sync[${F}] to scope to automatically derive one.")
  trait Make[F[_]] {
    def refOf[A](a: A): F[Ref[F, A]]
  }

  object Make extends MakeInstances

  private[kernel] trait MakeInstances extends MakeLowPriorityInstances {
    implicit def concurrentInstance[F[_]](implicit F: GenConcurrent[F, _]): Make[F] =
      new Make[F] {
        override def refOf[A](a: A): F[Ref[F, A]] = F.ref(a)
      }
  }

  private[kernel] trait MakeLowPriorityInstances {
    implicit def syncInstance[F[_]](implicit F: Sync[F]): Make[F] =
      new Make[F] {
        override def refOf[A](a: A): F[Ref[F, A]] = F.delay(unsafe(a))
      }
  }

  /**
   * Builds a `Ref` value for data types that are [[Sync]]
   *
   * This builder uses the
   * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type]]
   * technique.
   *
   * {{{
   *   Ref[IO].of(10) <-> Ref.of[IO, Int](10)
   * }}}
   *
   * @see
   *   [[of]]
   */
  def apply[F[_]](implicit mk: Make[F]): ApplyBuilders[F] = new ApplyBuilders(mk)

  /**
   * Creates a thread-safe, concurrent mutable reference initialized to the supplied value.
   *
   * {{{
   *   import cats.effect.IO
   *   import cats.effect.kernel.Ref
   *
   *   for {
   *     intRef <- Ref.of[IO, Int](10)
   *     ten <- intRef.get
   *   } yield ten
   * }}}
   */
  def of[F[_], A](a: A)(implicit mk: Make[F]): F[Ref[F, A]] = mk.refOf(a)

  /**
   * Creates a `Ref` with empty content
   */
  def empty[F[_]: Make, A: Monoid]: F[Ref[F, A]] = of(Monoid[A].empty)

  /**
   * Creates a `Ref` starting with the value of the one in `source`.
   *
   * Updates of either of the Refs will not have an effect on the other (assuming A is
   * immutable).
   */
  def copyOf[F[_]: Make: FlatMap, A](source: Ref[F, A]): F[Ref[F, A]] =
    ofEffect(source.get)

  /**
   * Creates a `Ref` starting with the result of the effect `fa`.
   */
  def ofEffect[F[_]: Make: FlatMap, A](fa: F[A]): F[Ref[F, A]] =
    FlatMap[F].flatMap(fa)(of(_))

  /**
   * Like `apply` but returns the newly allocated `Ref` directly instead of wrapping it in
   * `F.delay`. This method is considered unsafe because it is not referentially transparent --
   * it allocates mutable state.
   *
   * This method uses the
   * [[http://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially Applied Type Params technique]],
   * so only effect type needs to be specified explicitly.
   *
   * Some care must be taken to preserve referential transparency:
   *
   * {{{
   *   import cats.effect.IO
   *   import cats.effect.kernel.Ref
   *
   *   class Counter private () {
   *     private val count = Ref.unsafe[IO, Int](0)
   *
   *     def increment: IO[Unit] = count.update(_ + 1)
   *     def total: IO[Int] = count.get
   *   }
   *
   *   object Counter {
   *     def apply(): IO[Counter] = IO(new Counter)
   *   }
   * }}}
   *
   * Such usage is safe, as long as the class constructor is not accessible and the public one
   * suspends creation in IO.
   *
   * The recommended alternative is accepting a `Ref[F, A]` as a parameter:
   *
   * {{{
   *   class Counter (count: Ref[IO, Int]) {
   *     // same body
   *   }
   *
   *   object Counter {
   *     def apply(): IO[Counter] = Ref[IO](0).map(new Counter(_))
   *   }
   * }}}
   */
  def unsafe[F[_], A](a: A)(implicit F: Sync[F]): Ref[F, A] = new SyncRef(a)

  /**
   * Builds a `Ref` value for data types that are [[Sync]] like [[of]] but initializes state
   * using another effect constructor
   */
  def in[F[_], G[_], A](a: A)(implicit F: Sync[F], G: Sync[G]): F[Ref[G, A]] =
    F.delay(unsafe(a))

  /**
   * Creates an instance focused on a component of another `Ref`'s value. Delegates every get
   * and modification to underlying `Ref`, so both instances are always in sync.
   *
   * Example:
   *
   * {{{
   *   case class Foo(bar: String, baz: Int)
   *
   *   val refA: Ref[IO, Foo] = ???
   *   val refB: Ref[IO, String] =
   *     Ref.lens[IO, Foo, String](refA)(_.bar, (foo: Foo) => (bar: String) => foo.copy(bar = bar))
   * }}}
   */
  def lens[F[_], A, B](ref: Ref[F, A])(get: A => B, set: A => B => A)(
      implicit F: Functor[F]): Ref[F, B] =
    new LensRef[F, A, B](ref)(get, set)

  @deprecated("Signature preserved for bincompat", "3.4.0")
  def lens[F[_], A, B <: AnyRef](
      ref: Ref[F, A],
      get: A => B,
      set: A => B => A,
      F: Sync[F]): Ref[F, B] =
    new LensRef[F, A, B](ref)(get, set)(F)

  final class ApplyBuilders[F[_]](val mk: Make[F]) extends AnyVal {

    /**
     * Creates a thread-safe, concurrent mutable reference initialized to the supplied value.
     *
     * @see
     *   [[Ref.of]]
     */
    def of[A](a: A): F[Ref[F, A]] = mk.refOf(a)

    /**
     * Creates a thread-safe, concurrent mutable reference initialized to the empty value.
     *
     * @see
     *   [[Ref.empty]]
     */
    def empty[A: Monoid]: F[Ref[F, A]] = of(Monoid[A].empty)
  }

  final private[kernel] class TransformedRef2[F[_], G[_], A](
      underlying: Ref[F, A],
      trans: F ~> G)(
      implicit G: Functor[G]
  ) extends Ref[G, A] {
    override def get: G[A] = trans(underlying.get)
    override def set(a: A): G[Unit] = trans(underlying.set(a))
    override def getAndSet(a: A): G[A] = trans(underlying.getAndSet(a))
    override def tryUpdate(f: A => A): G[Boolean] = trans(underlying.tryUpdate(f))
    override def tryModify[B](f: A => (A, B)): G[Option[B]] = trans(underlying.tryModify(f))
    override def update(f: A => A): G[Unit] = trans(underlying.update(f))
    override def modify[B](f: A => (A, B)): G[B] = trans(underlying.modify(f))
    override def tryModifyState[B](state: State[A, B]): G[Option[B]] =
      trans(underlying.tryModifyState(state))
    override def modifyState[B](state: State[A, B]): G[B] = trans(underlying.modifyState(state))

    override def access: G[(A, A => G[Boolean])] =
      G.compose[(A, *)].compose[A => *].map(trans(underlying.access))(trans(_))
  }

  @deprecated("Use TransformedRef2 with Functor[G] constraint", "3.6.0")
  final private[kernel] class TransformedRef[F[_], G[_], A](
      underlying: Ref[F, A],
      trans: F ~> G)(
      implicit F: Functor[F]
  ) extends Ref[G, A] {
    override def get: G[A] = trans(underlying.get)
    override def set(a: A): G[Unit] = trans(underlying.set(a))
    override def getAndSet(a: A): G[A] = trans(underlying.getAndSet(a))
    override def tryUpdate(f: A => A): G[Boolean] = trans(underlying.tryUpdate(f))
    override def tryModify[B](f: A => (A, B)): G[Option[B]] = trans(underlying.tryModify(f))
    override def update(f: A => A): G[Unit] = trans(underlying.update(f))
    override def modify[B](f: A => (A, B)): G[B] = trans(underlying.modify(f))
    override def tryModifyState[B](state: State[A, B]): G[Option[B]] =
      trans(underlying.tryModifyState(state))
    override def modifyState[B](state: State[A, B]): G[B] = trans(underlying.modifyState(state))

    override def access: G[(A, A => G[Boolean])] =
      trans(F.compose[(A, *)].compose[A => *].map(underlying.access)(trans(_)))
  }

  final private[kernel] class LensRef[F[_], A, B](underlying: Ref[F, A])(
      lensGet: A => B,
      lensSet: A => B => A
  )(implicit F: Functor[F])
      extends Ref[F, B] {

    def this(underlying: Ref[F, A], lensGet: A => B, lensSet: A => B => A, F: Sync[F]) =
      this(underlying)(lensGet, lensSet)(F)
    override def get: F[B] = F.map(underlying.get)(a => lensGet(a))

    override def set(b: B): F[Unit] = underlying.update(a => lensModify(a)(_ => b))

    override def getAndSet(b: B): F[B] =
      underlying.modify { a => (lensModify(a)(_ => b), lensGet(a)) }

    override def update(f: B => B): F[Unit] =
      underlying.update(a => lensModify(a)(f))

    override def modify[C](f: B => (B, C)): F[C] =
      underlying.modify { a =>
        val oldB = lensGet(a)
        val (b, c) = f(oldB)
        (lensSet(a)(b), c)
      }

    override def tryUpdate(f: B => B): F[Boolean] =
      F.map(tryModify(a => (f(a), ())))(_.isDefined)

    override def tryModify[C](f: B => (B, C)): F[Option[C]] =
      underlying.tryModify { a =>
        val oldB = lensGet(a)
        val (b, result) = f(oldB)
        (lensSet(a)(b), result)
      }

    override def tryModifyState[C](state: State[B, C]): F[Option[C]] = {
      val f = state.runF.value
      tryModify(a => f(a).value)
    }

    override def modifyState[C](state: State[B, C]): F[C] = {
      val f = state.runF.value
      modify(a => f(a).value)
    }

    override val access: F[(B, B => F[Boolean])] =
      F.map(underlying.access) {
        case (a, update) =>
          (lensGet(a), b => update(lensSet(a)(b)))
      }

    private def lensModify(s: A)(f: B => B): A = lensSet(s)(f(lensGet(s)))
  }

  implicit def catsInvariantForRef[F[_]: Functor]: Invariant[Ref[F, *]] =
    new Invariant[Ref[F, *]] {
      override def imap[A, B](fa: Ref[F, A])(f: A => B)(g: B => A): Ref[F, B] =
        new Ref[F, B] {
          override val get: F[B] = fa.get.map(f)
          override def set(a: B): F[Unit] = fa.set(g(a))
          override def getAndSet(a: B): F[B] = fa.getAndSet(g(a)).map(f)
          override val access: F[(B, B => F[Boolean])] =
            fa.access.map(_.bimap(f, _.compose(g)))
          override def tryUpdate(f2: B => B): F[Boolean] =
            fa.tryUpdate(g.compose(f2).compose(f))
          override def tryModify[C](f2: B => (B, C)): F[Option[C]] =
            fa.tryModify(f2.compose(f).map(_.leftMap(g)))
          override def update(f2: B => B): F[Unit] =
            fa.update(g.compose(f2).compose(f))
          override def modify[C](f2: B => (B, C)): F[C] =
            fa.modify(f2.compose(f).map(_.leftMap(g)))
          override def tryModifyState[C](state: State[B, C]): F[Option[C]] =
            fa.tryModifyState(state.dimap(f)(g))
          override def modifyState[C](state: State[B, C]): F[C] =
            fa.modifyState(state.dimap(f)(g))
        }
    }
}

trait RefSource[F[_], A] extends Serializable {

  /**
   * Obtains the current value.
   *
   * Since `Ref` is always guaranteed to have a value, the returned action completes immediately
   * after being bound.
   */
  def get: F[A]
}

object RefSource {
  implicit def catsFunctorForRefSource[F[_]: Functor]: Functor[RefSource[F, *]] =
    new Functor[RefSource[F, *]] {
      override def map[A, B](fa: RefSource[F, A])(f: A => B): RefSource[F, B] =
        new RefSource[F, B] {
          override def get: F[B] =
            fa.get.map(f)
        }
    }
}

trait RefSink[F[_], A] extends Serializable {

  /**
   * Sets the current value to `a`.
   *
   * The returned action completes after the reference has been successfully set.
   */
  def set(a: A): F[Unit]
}

object RefSink {
  implicit def catsContravariantForRefSink[F[_]]: Contravariant[RefSink[F, *]] =
    new Contravariant[RefSink[F, *]] {
      override def contramap[A, B](fa: RefSink[F, A])(f: B => A): RefSink[F, B] =
        new RefSink[F, B] {
          override def set(b: B): F[Unit] =
            fa.set(f(b))
        }
    }
}
