/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats.effect.internals;

@SuppressWarnings("unchecked")
public final class UnsafeConvergentCache<K, V> {

    // We need final-field semantics when:
    // 1) Growing an array and replacing the reference
    // 2) Inserting a new cache entry
    private Buffer<K, V> buffer = new Buffer<>(10);

    public void put(K k, V v) {
        if (buffer.put(k, v) != null) {
            // A collision occurred, grow the buffer and re-insert
            // all elements.
            buffer = buffer.grow();
        }
    }

    public V get(K k) {
        Node<K, V> entry = buffer.get(k);
        if (entry != null) {
            return entry.value;
        } else {
            return null;
        }
    }

    private static final class Node<A, B> {
        public final A key;
        public final B value;

        private Node(A key, B value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final class Buffer<K, V> {
        private final Node<K, V>[] array;
        private final int logSize;
        private final int size;
        private final int mask;

        public Buffer(int logSize) {
            this.logSize = logSize;
            this.size = 1 << logSize;
            this.mask = this.size - 1;
            this.array = (Node<K,V>[]) new Node<?, ?>[this.size];
        }

        public Buffer(int logSize, Node<K, V>[] array) {
            this.logSize = logSize;
            this.size = 1 << logSize;
            this.mask = this.size - 1;
            this.array = array;
        }

        public Node<K, V> put(K k, V v) {
            int hash = k.hashCode() & this.mask;
            Node<K, V> old = this.array[hash];
            if (old == null) {
                Node<K, V> newEntry = new Node<>(k, v);
                this.array[hash] = newEntry;
            }
            return old;
        }

        public Node<K, V> get(K k) {
            int hash = k.hashCode() & this.mask;
            return this.array[hash];
        }

        public Buffer grow() {
            int currentLogSize = this.logSize + 1;
            int currentSize = 1 << currentLogSize;
            int currentMask = currentSize - 1;
            Node<K, V>[] newArray = (Node<K, V>[]) new Node<?, ?>[currentSize];

            boolean done = false;
            while (!done) {
                boolean collided = false;
                for (int i = 0; i < this.size && !collided; i++) {
                    Node<K, V> entry = this.array[i];
                    if (entry != null) {
                        int hash = entry.key.hashCode() & currentMask;
                        if (newArray[hash] != null) {
                            System.out.println("collided at " + hash);
                            System.out.println(newArray[hash].value);
                            System.out.println(entry.value);
                            collided = true;
                        } else {
                            newArray[hash] = entry;
                        }
                    }
                }

                if (collided) {
                    currentLogSize += 1;
                    currentSize = 1 << currentLogSize;
                    currentMask = currentSize - 1;
                    newArray = (Node<K, V>[]) new Node<?, ?>[currentSize];
                } else {
                    done = true;
                }
            }

            // This is crucial, newArray needs to be fully initialized and primed
            // *before* Buffer is safely initialized, otherwise the final field
            // semantics won't be extended to newArray inside Buffer.
            return new Buffer(currentLogSize, newArray);
        }
    }

}
