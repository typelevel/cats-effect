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

package cats.effect.std

import cats._
import cats.conversions.all._
import cats.data._
import cats.effect.kernel._
import cats.syntax.all._

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This is a total map from K to Ref[F, V]. This allows us to use the Ref API backed by a
 * ConcurrentHashMap or similar.
 */
trait MapRef[F[_], K, V] extends Function1[K, Ref[F, V]] {

  /**
   * Access the reference for this Key
   */
  def apply(k: K): Ref[F, V]
}

object MapRef extends MapRefCompanionPlatform {

  /**
   * Creates a sharded map ref to reduce atomic contention on the Map, given an efficient and
   * equally distributed hash, the contention should allow for interaction like a general
   * datastructure.
   *
   * This uses universal hashCode and equality on K.
   */
  def ofShardedImmutableMap[F[_]: Concurrent, K, V](
      shardCount: Int
  ): F[MapRef[F, K, Option[V]]] = {
    assert(shardCount >= 1, "MapRef.sharded should have at least 1 shard")
    List
      .fill(shardCount)(())
      .traverse(_ => Concurrent[F].ref[Map[K, V]](Map.empty))
      .map(fromSeqRefs(_))
  }

  /**
   * Creates a sharded map ref to reduce atomic contention on the Map, given an efficient and
   * equally distributed hash, the contention should allow for interaction like a general
   * datastructure. Created in G, operates in F.
   *
   * This uses universal hashCode and equality on K.
   */
  def inShardedImmutableMap[G[_]: Sync, F[_]: Sync, K, V](
      shardCount: Int
  ): G[MapRef[F, K, Option[V]]] = Sync[G].defer {
    assert(shardCount >= 1, "MapRef.sharded should have at least 1 shard")
    List
      .fill(shardCount)(())
      .traverse(_ => Ref.in[G, F, Map[K, V]](Map.empty))
      .map(fromSeqRefs(_))
  }

  /**
   * Creates a sharded map ref from a sequence of refs.
   *
   * This uses universal hashCode and equality on K.
   */
  def fromSeqRefs[F[_]: Functor, K, V](
      seq: scala.collection.immutable.Seq[Ref[F, Map[K, V]]]
  ): MapRef[F, K, Option[V]] = {
    val array = seq.toArray
    val shardCount = seq.size
    val refFunction = { (k: K) =>
      val location = Math.abs(k.## % shardCount)
      array(location)
    }
    k => fromSingleImmutableMapRef(refFunction(k)).apply(k)
  }

  /**
   * Heavy Contention on Use
   *
   * This uses universal hashCode and equality on K.
   */
  def ofSingleImmutableMap[F[_]: Concurrent, K, V](
      map: Map[K, V] = Map.empty[K, V]): F[MapRef[F, K, Option[V]]] =
    Concurrent[F].ref(map).map(fromSingleImmutableMapRef[F, K, V](_))

  /**
   * Heavy Contention on Use. Created in G, operates in F.
   *
   * This uses universal hashCode and equality on K.
   */
  def inSingleImmutableMap[G[_]: Sync, F[_]: Sync, K, V](
      map: Map[K, V] = Map.empty[K, V]): G[MapRef[F, K, Option[V]]] =
    Ref.in[G, F, Map[K, V]](map).map(fromSingleImmutableMapRef[F, K, V](_))

  /**
   * Heavy Contention on Use, Allows you to access the underlying map through processes outside
   * of this interface. Useful for Atomic Map[K, V] => Map[K, V] interactions.
   *
   * This uses universal hashCode and equality on K.
   */
  def fromSingleImmutableMapRef[F[_]: Functor, K, V](
      ref: Ref[F, Map[K, V]]): MapRef[F, K, Option[V]] =
    k => Ref.lens(ref)(_.get(k), m => _.fold(m - k)(v => m + (k -> v)))

  private class ConcurrentHashMapImpl[F[_], K, V](chm: ConcurrentHashMap[K, V], sync: Sync[F])
      extends MapRef[F, K, Option[V]] {
    private implicit def syncF: Sync[F] = sync

    val fnone0: F[None.type] = sync.pure(None)
    def fnone[A]: F[Option[A]] = fnone0.widen[Option[A]]

    def delay[A](a: => A): F[A] = sync.delay(a)

    class HandleRef(k: K) extends Ref[F, Option[V]] {

      def access: F[(Option[V], Option[V] => F[Boolean])] =
        delay {
          val hasBeenCalled = new AtomicBoolean(false)
          val init = chm.get(k)
          if (init == null) {
            val set: Option[V] => F[Boolean] = { (opt: Option[V]) =>
              opt match {
                case None =>
                  delay(hasBeenCalled.compareAndSet(false, true) && !chm.containsKey(k))
                case Some(newV) =>
                  delay {
                    // it was initially empty
                    hasBeenCalled.compareAndSet(false, true) && chm.putIfAbsent(k, newV) == null
                  }
              }
            }
            (None, set)
          } else {
            val set: Option[V] => F[Boolean] = { (opt: Option[V]) =>
              opt match {
                case None =>
                  delay(hasBeenCalled.compareAndSet(false, true) && chm.remove(k, init))
                case Some(newV) =>
                  delay(hasBeenCalled.compareAndSet(false, true) && chm.replace(k, init, newV))
              }
            }
            (Some(init), set)
          }
        }

      def get: F[Option[V]] =
        delay {
          Option(chm.get(k))
        }

      override def getAndSet(a: Option[V]): F[Option[V]] =
        a match {
          case None =>
            delay(Option(chm.remove(k)))
          case Some(v) =>
            delay(Option(chm.put(k, v)))
        }

      def modify[B](f: Option[V] => (Option[V], B)): F[B] = {
        def loop: F[B] = tryModify(f).flatMap {
          case None => loop
          case Some(b) => sync.pure(b)
        }
        loop
      }

      def modifyState[B](state: State[Option[V], B]): F[B] =
        modify(state.run(_).value)

      def set(a: Option[V]): F[Unit] =
        a match {
          case None => delay { chm.remove(k); () }
          case Some(v) => delay { chm.put(k, v); () }
        }

      def tryModify[B](f: Option[V] => (Option[V], B)): F[Option[B]] =
        // we need the suspend because we do effects inside
        delay {
          val init = chm.get(k)
          if (init == null) {
            f(None) match {
              case (None, b) =>
                // no-op
                sync.pure(b.some)
              case (Some(newV), b) =>
                if (chm.putIfAbsent(k, newV) == null) sync.pure(b.some)
                else fnone
            }
          } else {
            f(Some(init)) match {
              case (None, b) =>
                if (chm.remove(k, init)) sync.pure(Some(b))
                else fnone[B]
              case (Some(next), b) =>
                if (chm.replace(k, init, next)) sync.pure(Some(b))
                else fnone[B]

            }
          }
        }.flatten

      def tryModifyState[B](state: State[Option[V], B]): F[Option[B]] =
        tryModify(state.run(_).value)

      def tryUpdate(f: Option[V] => Option[V]): F[Boolean] =
        tryModify { opt => (f(opt), ()) }.map(_.isDefined)

      def update(f: Option[V] => Option[V]): F[Unit] = {
        def loop: F[Unit] = tryUpdate(f).flatMap {
          case true => sync.unit
          case false => loop
        }
        loop
      }
    }

    def apply(k: K): Ref[F, Option[V]] = new HandleRef(k)
  }

  /**
   * Takes a ConcurrentHashMap, giving you access to the mutable state from the constructor.
   *
   * This uses universal hashCode and equality on K.
   */
  def fromConcurrentHashMap[F[_]: Sync, K, V](
      map: ConcurrentHashMap[K, V]): MapRef[F, K, Option[V]] =
    new ConcurrentHashMapImpl[F, K, V](map, Sync[F])

  /**
   * This allocates mutable memory, so it has to be inside F. The way to use things like this is
   * to allocate one then `.map` them inside of constructors that need to access them.
   *
   * It is usually a mistake to have a `G[RefMap[F, K, V]]` field. You want `RefMap[F, K, V]`
   * field which means the thing that needs it will also have to be inside of `F[_]`, which is
   * because it needs access to mutable state so allocating it is also an effect.
   *
   * This uses universal hashCode and equality on K.
   */
  def inConcurrentHashMap[G[_]: Sync, F[_]: Sync, K, V](
      initialCapacity: Int = 16,
      loadFactor: Float = 0.75f,
      concurrencyLevel: Int = 16
  ): G[MapRef[F, K, Option[V]]] =
    Sync[G]
      .delay(new ConcurrentHashMap[K, V](initialCapacity, loadFactor, concurrencyLevel))
      .map(fromConcurrentHashMap[F, K, V])

  /**
   * This allocates mutable memory, so it has to be inside F. The way to use things like this is
   * to allocate one then `.map` them inside of constructors that need to access them.
   *
   * It is usually a mistake to have a `F[RefMap[F, K, V]]` field. You want `RefMap[F, K, V]`
   * field which means the thing that needs it will also have to be inside of `F[_]`, which is
   * because it needs access to mutable state so allocating it is also an effect.
   *
   * This uses universal hashCode and equality on K.
   */
  def ofConcurrentHashMap[F[_]: Sync, K, V](
      initialCapacity: Int = 16,
      loadFactor: Float = 0.75f,
      concurrencyLevel: Int = 16
  ): F[MapRef[F, K, Option[V]]] =
    Sync[F]
      .delay(new ConcurrentHashMap[K, V](initialCapacity, loadFactor, concurrencyLevel))
      .map(fromConcurrentHashMap[F, K, V])

  /**
   * Takes a scala.collection.concurrent.Map, giving you access to the mutable state from the
   * constructor.
   */
  def fromScalaConcurrentMap[F[_]: Sync, K, V](
      map: scala.collection.concurrent.Map[K, V]): MapRef[F, K, Option[V]] =
    new ScalaConcurrentMapImpl[F, K, V](map)

  private class ScalaConcurrentMapImpl[F[_], K, V](map: scala.collection.concurrent.Map[K, V])(
      implicit sync: Sync[F])
      extends MapRef[F, K, Option[V]] {

    val fnone0: F[None.type] = sync.pure(None)
    def fnone[A]: F[Option[A]] = fnone0.widen[Option[A]]

    class HandleRef(k: K) extends Ref[F, Option[V]] {
      def access: F[(Option[V], Option[V] => F[Boolean])] =
        sync.delay {
          val hasBeenCalled = new AtomicBoolean(false)
          val init = map.get(k)
          init match {
            case None =>
              val set: Option[V] => F[Boolean] = { (opt: Option[V]) =>
                opt match {
                  case None =>
                    sync.delay(hasBeenCalled.compareAndSet(false, true) && !map.contains(k))
                  case Some(newV) =>
                    sync.delay {
                      // it was initially empty
                      hasBeenCalled
                        .compareAndSet(false, true) && map.putIfAbsent(k, newV).isEmpty
                    }
                }
              }
              (None, set)
            case Some(old) =>
              val set: Option[V] => F[Boolean] = { (opt: Option[V]) =>
                opt match {
                  case None =>
                    sync.delay(hasBeenCalled.compareAndSet(false, true) && map.remove(k, old))
                  case Some(newV) =>
                    sync.delay(
                      hasBeenCalled.compareAndSet(false, true) && map.replace(k, old, newV))
                }
              }
              (init, set)
          }
        }

      def get: F[Option[V]] =
        sync.delay(map.get(k))

      override def getAndSet(a: Option[V]): F[Option[V]] =
        a match {
          case None =>
            sync.delay(map.remove(k))
          case Some(v) =>
            sync.delay(map.put(k, v))
        }

      def modify[B](f: Option[V] => (Option[V], B)): F[B] = {
        def loop: F[B] = tryModify(f).flatMap {
          case None => loop
          case Some(b) => sync.pure(b)
        }
        loop
      }

      def modifyState[B](state: State[Option[V], B]): F[B] =
        modify(state.run(_).value)

      def set(a: Option[V]): F[Unit] =
        a match {
          case None => sync.delay { map.remove(k); () }
          case Some(v) => sync.delay { map.put(k, v); () }
        }

      def tryModify[B](
          f: Option[V] => (Option[V], B))
          : F[Option[B]] = // we need the suspend because we do effects inside
        sync.delay {
          val init = map.get(k)
          init match {
            case None =>
              f(None) match {
                case (None, b) =>
                  // no-op
                  sync.pure(b.some)
                case (Some(newV), b) =>
                  sync.delay(map.putIfAbsent(k, newV).fold[Option[B]](b.some)(_ => None))
              }
            case Some(initV) =>
              f(init) match {
                case (None, b) =>
                  if (map.remove(k, initV)) sync.pure(b.some)
                  else fnone[B]
                case (Some(next), b) =>
                  if (map.replace(k, initV, next)) sync.pure(b.some)
                  else fnone[B]
              }
          }
        }.flatten

      def tryModifyState[B](state: State[Option[V], B]): F[Option[B]] =
        tryModify(state.run(_).value)

      def tryUpdate(f: Option[V] => Option[V]): F[Boolean] =
        tryModify { opt => (f(opt), ()) }.map(_.isDefined)

      def update(f: Option[V] => Option[V]): F[Unit] = {
        def loop: F[Unit] = tryUpdate(f).flatMap {
          case true => sync.unit
          case false => loop
        }
        loop
      }
    }

    /**
     * Access the reference for this Key
     */
    def apply(k: K): Ref[F, Option[V]] = new HandleRef(k)

  }

  implicit def mapRefInvariant[F[_]: Functor, K]: Invariant[MapRef[F, K, *]] =
    new MapRefInvariant[F, K]

  private class MapRefInvariant[F[_]: Functor, K] extends Invariant[MapRef[F, K, *]] {
    override def imap[V, V0](fa: MapRef[F, K, V])(f: V => V0)(g: V0 => V): MapRef[F, K, V0] =
      new MapRef[F, K, V0] {

        override def apply(k: K): Ref[F, V0] = fa(k).imap(f)(g)
      }
  }

  /**
   * Operates with default and anytime default is present instead information is removed from
   * underlying ref. This is very useful as a default state can be used to prevent space leaks
   * over high arity maprefs.
   *
   * Also useful for anytime a shared storage location is used for a ref, i.e. DB or Redis to
   * not waste space. // Some(default) -- None
   */
  def defaultedRef[F[_]: Functor, A: Eq](ref: Ref[F, Option[A]], default: A): Ref[F, A] =
    new LiftedRefDefaultStorage[F, A](ref, default)

  def defaultedMapRef[F[_]: Functor, K, A: Eq](
      mapref: MapRef[F, K, Option[A]],
      default: A): MapRef[F, K, A] = {
    new MapRef[F, K, A] {
      def apply(k: K): Ref[F, A] = defaultedRef(mapref(k), default)
    }
  }

  /**
   * Operates with default and anytime default is present instead information is removed from
   * underlying ref.
   */
  private class LiftedRefDefaultStorage[F[_]: Functor, A: Eq](
      val ref: Ref[F, Option[A]],
      val default: A
  ) extends Ref[F, A] {
    def get: F[A] = ref.get.map(_.getOrElse(default))

    def set(a: A): F[Unit] = {
      if (a =!= default) ref.set(a.some)
      else ref.set(None)
    }

    def access: F[(A, A => F[Boolean])] = ref.access.map {
      case (opt, cb) =>
        (
          opt.getOrElse(default),
          { (s: A) =>
            if (s =!= default) cb(s.some)
            else cb(None)
          })
    }

    def tryUpdate(f: A => A): F[Boolean] =
      tryModify { (s: A) => (f(s), ()) }.map(_.isDefined)

    def tryModify[B](f: A => (A, B)): F[Option[B]] =
      ref.tryModify { opt =>
        val s = opt.getOrElse(default)
        val (after, out) = f(s)
        if (after =!= default) (after.some, out)
        else (None, out)
      }

    def update(f: A => A): F[Unit] =
      modify((s: A) => (f(s), ()))

    def modify[B](f: A => (A, B)): F[B] =
      ref.modify { opt =>
        val a = opt.getOrElse(default)
        val (out, b) = f(a)
        if (out =!= default) (out.some, b)
        else (None, b)
      }

    def tryModifyState[B](state: cats.data.State[A, B]): F[Option[B]] =
      tryModify { s => state.run(s).value }

    def modifyState[B](state: cats.data.State[A, B]): F[B] =
      modify { s => state.run(s).value }
  }

  implicit def mapRefOptionSyntax[F[_], K, V](
      mRef: MapRef[F, K, Option[V]]
  ): MapRefOptionOps[F, K, V] =
    new MapRefOptionOps(mRef)

  final class MapRefOptionOps[F[_], K, V] private[MapRef] (
      private val mRef: MapRef[F, K, Option[V]]) {
    def unsetKey(k: K): F[Unit] =
      mRef(k).set(None)
    def setKeyValue(k: K, v: V): F[Unit] =
      mRef(k).set(v.some)
    def getAndSetKeyValue(k: K, v: V): F[Option[V]] =
      mRef(k).getAndSet(v.some)

    def updateKeyValueIfSet(k: K, f: V => V): F[Unit] =
      mRef(k).update {
        case None => None
        case Some(v) => f(v).some
      }

    def modifyKeyValueIfSet[B](k: K, f: V => (V, B)): F[Option[B]] =
      mRef(k).modify {
        case None => (None, None)
        case Some(v) =>
          val (set, out) = f(v)
          (set.some, out.some)
      }
  }
}
