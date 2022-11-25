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

package cats.effect.kernel;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

// defined in Java since Scala doesn't let us define static fields
@SuppressWarnings({"rawtypes", "serial", "unchecked"})
final class SyncRef extends AbstractSyncRef {

  private volatile Object value;

  SyncRef(Object F, Object value) {
    super((Sync) F);
    this.value = value;
  }

  static final AtomicReferenceFieldUpdater<SyncRef, Object> updater =
      AtomicReferenceFieldUpdater.newUpdater(SyncRef.class, Object.class, "value");
}
