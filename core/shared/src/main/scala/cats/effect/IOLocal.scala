/*
 * Copyright 2020-2022 Typelevel
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

import cats.kernel.Hash

import scala.reflect.ClassTag

sealed trait IOLocal[A] {

  def get: IO[A]

  def set(value: A): IO[Unit]

  def reset: IO[Unit]

  def update(f: A => A): IO[Unit]

  def modify[B](f: A => (A, B)): IO[B]

  def getAndSet(value: A): IO[A]

  def getAndReset: IO[A]

}

object IOLocal {

  def apply[A](default: A): IO[IOLocal[A]] =
    IO(new SelfKeyedIOLocal[A](default))

  /**
   * Creates an [[IOLocal]] instance keyed on `k`. Two instances of `IOLocal` created with equal
   * keys will operate the same state when their operations are sequenced together.
   *
   * Warning: calling this twice with equal keys but different value types will result in
   * runtime errors.
   *
   * Note: because the equality comparison is user-controlled, it can never be guaranteed that
   * an instance created with a different key will not interfere with this instance.
   */
  def forKey[K: Hash, A](key: K, default: A): IOLocal[A] =
    new KeyedIOLocal[K, A](key, default)

  /**
   * Similar to [[forKey]], but its state is shared only with other instances created by calling
   * [[forSingletonKey]] with identically the same object.
   *
   * Warning: calling this twice with equal keys but different value types will result in
   * runtime errors.
   *
   * {{{
   *   private object FooKey
   *   val fooLocal: IOLocal[Foo] = IOLocal.forSingletonKey[Foo](FooKey)
   * }}}
   */
  def forSingletonKey[A](key: AnyRef, default: A): IOLocal[A] =
    new SingletonKeyedIOLocal[A](key, default)

  /*
   * Creates an IOLocal[A], keyed on the class `A`. All instances created for exactly
   * the same class or trait will share the same state on a given fiber. Instances
   * created for subtypes or supertypes are entirely independent.
   */
  def forClass[A: ClassTag](default: A): IOLocal[A] =
    new ClassTagIOLocal[A](default)

  private[effect] class IOLocalImpl[A](default: A) extends IOLocal[A] { self =>
    override def get: IO[A] =
      IO.Local(state => (state, state.get(self).map(_.asInstanceOf[A]).getOrElse(default)))

    override def set(value: A): IO[Unit] =
      IO.Local(state => (state + (self -> value), ()))

    override def reset: IO[Unit] =
      IO.Local(state => (state - self, ()))

    override def update(f: A => A): IO[Unit] =
      get.flatMap(a => set(f(a)))

    override def modify[B](f: A => (A, B)): IO[B] =
      get.flatMap { a =>
        val (a2, b) = f(a)
        set(a2).as(b)
      }

    override def getAndSet(value: A): IO[A] =
      get <* set(value)

    override def getAndReset: IO[A] =
      get <* reset

  }

  private[effect] class SelfKeyedIOLocal[A](default: A) extends IOLocalImpl[A](default)

  private[effect] class KeyedIOLocal[K, A](private val key: K, default: A)(implicit ev: Hash[K])
      extends IOLocalImpl[A](default) {
    override def equals(that: Any): Boolean =
      that.isInstanceOf[KeyedIOLocal[_, _]] &&
        ev.eqv(that.asInstanceOf[KeyedIOLocal[K, _]].key, key)

    override def hashCode(): Int = ev.hash(key)
  }

  private[effect] class SingletonKeyedIOLocal[A](private val key: AnyRef, default: A)
      extends IOLocalImpl[A](default) {
    override def equals(that: Any): Boolean =
      that.isInstanceOf[SingletonKeyedIOLocal[_]] &&
        (that.asInstanceOf[SingletonKeyedIOLocal[_]].key eq key)

    override def hashCode(): Int = key.hashCode()
  }

  private[effect] class ClassTagIOLocal[A](default: A)(implicit private val ct: ClassTag[A])
      extends IOLocalImpl[A](default) {
    override def equals(that: Any): Boolean =
      this.isInstanceOf[ClassTagIOLocal[_]] &&
        (that.asInstanceOf[ClassTagIOLocal[_]].ct == ct)

    override def hashCode(): Int = ct.hashCode()
  }
}
