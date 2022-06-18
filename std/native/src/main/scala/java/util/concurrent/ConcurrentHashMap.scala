/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package java.util.concurrent

import java.io.Serializable
import java.util._

class ConcurrentHashMap[K, V] private (initialCapacity: Int, loadFactor: Float)
    extends AbstractMap[K, V]
    with ConcurrentMap[K, V]
    with Serializable {

  import ConcurrentHashMap._

  def this() =
    this(ConcurrentHashMap.DEFAULT_INITIAL_CAPACITY, ConcurrentHashMap.DEFAULT_LOAD_FACTOR)

  def this(initialCapacity: Int) =
    this(initialCapacity, ConcurrentHashMap.DEFAULT_LOAD_FACTOR)

  def this(initialMap: java.util.Map[_ <: K, _ <: V]) = {
    this(initialMap.size())
    putAll(initialMap)
  }

  def this(initialCapacity: Int, loadFactor: Float, concurrencyLevel: Int) =
    this(initialCapacity, loadFactor) // ignore concurrencyLevel

  private[this] val inner: InnerHashMap[K, V] =
    new InnerHashMap[K, V](initialCapacity, loadFactor)

  override def size(): Int =
    inner.size()

  override def isEmpty(): Boolean =
    inner.isEmpty()

  override def get(key: Any): V =
    inner.get(key)

  override def containsKey(key: Any): Boolean =
    inner.containsKey(key)

  override def containsValue(value: Any): Boolean =
    inner.containsValue(value)

  override def put(key: K, value: V): V =
    inner.put(key, value)

  override def remove(key: Any): V =
    inner.remove(key)

  override def clear(): Unit =
    inner.clear()

  override def keySet(): ConcurrentHashMap.KeySetView[K, V] = {
    // Allow null as sentinel
    new ConcurrentHashMap.KeySetView[K, V](this.inner, null.asInstanceOf[V])
  }

  def keySet(mappedValue: V): ConcurrentHashMap.KeySetView[K, V] = {
    if (mappedValue == null)
      throw new NullPointerException()
    new ConcurrentHashMap.KeySetView[K, V](this.inner, mappedValue)
  }

  override def values(): Collection[V] =
    inner.values()

  override def entrySet(): Set[Map.Entry[K, V]] =
    inner.entrySet()

  override def hashCode(): Int =
    inner.hashCode()

  override def toString(): String =
    inner.toString()

  override def equals(o: Any): Boolean =
    inner.equals(o)

  override def putIfAbsent(key: K, value: V): V =
    inner.putIfAbsent(key, value)

  override def remove(key: Any, value: Any): Boolean =
    inner.remove(key, value)

  override def replace(key: K, oldValue: V, newValue: V): Boolean =
    inner.replace(key, oldValue, newValue)

  override def replace(key: K, value: V): V =
    inner.replace(key, value)

  def contains(value: Any): Boolean =
    containsValue(value)

  def keys(): Enumeration[K] =
    Collections.enumeration(inner.keySet())

  def elements(): Enumeration[V] =
    Collections.enumeration(values())
}

object ConcurrentHashMap {

  /**
   * Computes the improved hash of an original (`any.hashCode()`) hash.
   */
  @inline private def improveHash(originalHash: Int): Int = {
    /* Improve the hash by xoring the high 16 bits into the low 16 bits just in
     * case entropy is skewed towards the high-value bits. We only use the
     * lowest bits to determine the hash bucket.
     *
     * This function is also its own inverse. That is, for all ints i,
     * improveHash(improveHash(i)) = i
     * this allows us to retrieve the original hash when we need it, and that
     * is why unimproveHash simply forwards to this method.
     */
    originalHash ^ (originalHash >>> 16)
  }

  /**
   * Performs the inverse operation of improveHash.
   *
   * In this case, it happens to be identical to improveHash.
   */
  @inline private def unimproveHash(improvedHash: Int): Int =
    improveHash(improvedHash)

  /**
   * Computes the improved hash of this key
   */
  // @inline private def computeHash(k: Any): Int =
  //   if (k == null) 0
  //   else improveHash(k.hashCode())

  private[util] class Node[K, V](
      val key: K,
      val hash: Int,
      var value: V,
      var previous: Node[K, V],
      var next: Node[K, V])
      extends Map.Entry[K, V] {

    def getKey(): K = key

    def getValue(): V = value

    def setValue(v: V): V = {
      val oldValue = value
      value = v
      oldValue
    }

    override def equals(that: Any): Boolean = that match {
      case that: Map.Entry[_, _] =>
        Objects.equals(getKey(), that.getKey()) &&
        Objects.equals(getValue(), that.getValue())
      case _ =>
        false
    }

    override def hashCode(): Int =
      unimproveHash(hash) ^ Objects.hashCode(value)

    override def toString(): String =
      "" + getKey() + "=" + getValue()
  }

  /**
   * Inner HashMap that contains the real implementation of a ConcurrentHashMap.
   *
   * It is a null-rejecting hash map because some algorithms rely on the fact that `get(key) ==
   * null` means the key was not in the map.
   *
   * It also has snapshotting iterators to make sure they are *weakly consistent*.
   */
  private final class InnerHashMap[K, V](initialCapacity: Int, loadFactor: Float)
      extends NullRejectingHashMap[K, V](initialCapacity, loadFactor) {

    private[util] def nodeIterator(): Iterator[ConcurrentHashMap.Node[K, V]] =
      new NodeIterator

    private[util] def keyIterator(): Iterator[K] =
      new KeyIterator

    private[util] def valueIterator(): Iterator[V] =
      new ValueIterator

    private def makeSnapshot(): ArrayList[Node[K, V]] = {
      val snapshot = new ArrayList[Node[K, V]](size())
      val iter = new NodeIterator
      while (iter.hasNext())
        snapshot.add(iter.next())
      snapshot
    }

    private final class NodeIterator extends AbstractCHMIterator[Node[K, V]] {
      protected[this] def extract(node: Node[K, V]): Node[K, V] = node
    }

    private final class KeyIterator extends AbstractCHMIterator[K] {
      protected[this] def extract(node: Node[K, V]): K = node.key
    }

    private final class ValueIterator extends AbstractCHMIterator[V] {
      protected[this] def extract(node: Node[K, V]): V = node.value
    }

    private abstract class AbstractCHMIterator[A] extends Iterator[A] {
      private[this] val innerIter = makeSnapshot().iterator()
      private[this] var lastNode: Node[K, V] = _ // null

      protected[this] def extract(node: Node[K, V]): A

      def hasNext(): Boolean =
        innerIter.hasNext()

      def next(): A = {
        val node = innerIter.next()
        lastNode = node
        extract(node)
      }

      override def remove(): Unit = ???
    }
  }

  class KeySetView[K, V] private[ConcurrentHashMap] (
      innerMap: InnerHashMap[K, V],
      defaultValue: V)
      extends Set[K]
      with Serializable {

    def getMappedValue(): V = defaultValue

    def contains(o: Any): Boolean = innerMap.containsKey(o)

    def remove(o: Any): Boolean = innerMap.remove(o) != null

    def iterator(): Iterator[K] = innerMap.keySet().iterator()

    def size(): Int = innerMap.size()

    def isEmpty(): Boolean = innerMap.isEmpty()

    def toArray(): Array[AnyRef] = innerMap.keySet().toArray()

    def toArray[T](a: Array[T with Object]): Array[T with Object] =
      innerMap.keySet().toArray(a).asInstanceOf[Array[T with Object]]

    def add(e: K): Boolean = {
      if (defaultValue == null) {
        throw new UnsupportedOperationException()
      }
      innerMap.putIfAbsent(e, defaultValue) == null
    }

    override def toString(): String = innerMap.keySet().toString

    def containsAll(c: Collection[_]): Boolean = innerMap.keySet().containsAll(c)

    def addAll(c: Collection[_ <: K]): Boolean = {
      if (defaultValue == null) {
        throw new UnsupportedOperationException()
      }
      val iter = c.iterator()
      var changed = false
      while (iter.hasNext())
        changed = innerMap.putIfAbsent(iter.next(), defaultValue) == null || changed
      changed
    }

    def removeAll(c: Collection[_]): Boolean = innerMap.keySet().removeAll(c)

    def retainAll(c: Collection[_]): Boolean = innerMap.keySet().retainAll(c)

    def clear(): Unit = innerMap.clear()
  }

  def newKeySet[K](): KeySetView[K, Boolean] =
    newKeySet[K](ConcurrentHashMap.DEFAULT_INITIAL_CAPACITY)

  def newKeySet[K](initialCapacity: Int): KeySetView[K, Boolean] = {
    val inner =
      new InnerHashMap[K, Boolean](initialCapacity, ConcurrentHashMap.DEFAULT_LOAD_FACTOR)
    new KeySetView[K, Boolean](inner, true)
  }

  private[util] final val DEFAULT_INITIAL_CAPACITY = 16
  private[util] final val DEFAULT_LOAD_FACTOR = 0.75f
}

/**
 * A subclass of `HashMap` that systematically rejects `null` keys and values.
 *
 * This class is used as the implementation of some other hashtable-like data structures that
 * require non-`null` keys and values to correctly implement their specifications.
 */
private[util] class NullRejectingHashMap[K, V](initialCapacity: Int, loadFactor: Float)
    extends HashMap[K, V](initialCapacity, loadFactor) {

  def this() =
    this(ConcurrentHashMap.DEFAULT_INITIAL_CAPACITY, ConcurrentHashMap.DEFAULT_LOAD_FACTOR)

  def this(initialCapacity: Int) =
    this(initialCapacity, ConcurrentHashMap.DEFAULT_LOAD_FACTOR)

  def this(m: Map[_ <: K, _ <: V]) = {
    this(m.size())
    putAll(m)
  }

  // Use Nodes that will reject `null`s in `setValue()`
  private[util] def newNode(
      key: K,
      hash: Int,
      value: V,
      previous: ConcurrentHashMap.Node[K, V],
      next: ConcurrentHashMap.Node[K, V]): ConcurrentHashMap.Node[K, V] = {
    new NullRejectingHashMap.Node(key, hash, value, previous, next)
  }

  override def get(key: Any): V = {
    if (key == null)
      throw new NullPointerException()
    super.get(key)
  }

  override def containsKey(key: Any): Boolean = {
    if (key == null)
      throw new NullPointerException()
    super.containsKey(key)
  }

  override def put(key: K, value: V): V = {
    if (key == null || value == null)
      throw new NullPointerException()
    super.put(key, value)
  }

  override def putIfAbsent(key: K, value: V): V = {
    if (value == null)
      throw new NullPointerException()
    val old = get(key) // throws if `key` is null
    if (old == null)
      super.put(key, value)
    old
  }

  @noinline
  override def putAll(m: Map[_ <: K, _ <: V]): Unit = {
    /* The only purpose of `impl` is to capture the wildcards as named types,
     * so that we prevent type inference from inferring deprecated existential
     * types.
     */
    @inline
    def impl[K1 <: K, V1 <: V](m: Map[K1, V1]): Unit = {
      val iter = m.entrySet().iterator()
      while (iter.hasNext()) {
        val entry = iter.next()
        put(entry.getKey(), entry.getValue())
      }
    }
    impl(m)
  }

  override def remove(key: Any): V = {
    if (key == null)
      throw new NullPointerException()
    super.remove(key)
  }

  override def remove(key: Any, value: Any): Boolean = {
    val old = get(key) // throws if `key` is null
    if (old != null && old.equals(value)) { // false if `value` is null
      super.remove(key)
      true
    } else {
      false
    }
  }

  override def replace(key: K, oldValue: V, newValue: V): Boolean = {
    if (oldValue == null || newValue == null)
      throw new NullPointerException()
    val old = get(key) // throws if `key` is null
    if (oldValue.equals(old)) { // false if `old` is null
      super.put(key, newValue)
      true
    } else {
      false
    }
  }

  override def replace(key: K, value: V): V = {
    if (value == null)
      throw new NullPointerException()
    val old = get(key) // throws if `key` is null
    if (old != null)
      super.put(key, value)
    old
  }

  override def containsValue(value: Any): Boolean = {
    if (value == null)
      throw new NullPointerException()
    super.containsValue(value)
  }

  override def clone(): AnyRef =
    new NullRejectingHashMap[K, V](this)
}

private object NullRejectingHashMap {
  private final class Node[K, V](
      key: K,
      hash: Int,
      value: V,
      previous: ConcurrentHashMap.Node[K, V],
      next: ConcurrentHashMap.Node[K, V])
      extends ConcurrentHashMap.Node[K, V](key, hash, value, previous, next) {

    override def setValue(v: V): V = {
      if (v == null)
        throw new NullPointerException()
      super.setValue(v)
    }
  }
}
