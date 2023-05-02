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

package cats.effect.unsafe;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Base class for `TimerSkipList#Node`, because we can't use `AtomicReferenceFieldUpdater` from
 * Scala.
 */
@SuppressWarnings("serial") // do not serialize this!
abstract class TimerSkipListNodeBase<C, N extends TimerSkipListNodeBase<C, N>>
    extends AtomicReference<N> {

  private volatile C callback;

  @SuppressWarnings("rawtypes")
  private static final AtomicReferenceFieldUpdater<TimerSkipListNodeBase, Object> CALLBACK =
      AtomicReferenceFieldUpdater.newUpdater(TimerSkipListNodeBase.class, Object.class, "callback");

  protected TimerSkipListNodeBase(C cb, N next) {
    super(next);
    this.callback = cb;
  }

  public final N getNext() {
    return this.get(); // could be `getAcquire`
  }

  public final boolean casNext(N ov, N nv) {
    return this.compareAndSet(ov, nv);
  }

  public final C getCb() {
    return this.callback; // could be `getAcquire`
  }

  public final boolean casCb(C ov, C nv) {
    return CALLBACK.compareAndSet(this, ov, nv);
  }
}
