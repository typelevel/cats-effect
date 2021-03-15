/*
 * Copyright 2020-2021 Typelevel
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

package cats.effect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicBoolean;

class AtomicBooleanCompat extends AtomicBoolean {

  public static final long serialVersionUID = 1L;

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static final MethodType GET_METHOD_TYPE = MethodType.methodType(boolean.class);
  private static final MethodType SET_METHOD_TYPE = MethodType.methodType(void.class, boolean.class);
  private static final MethodHandle GET_ACQUIRE_METHOD_HANDLE;
  private static final MethodHandle SET_RELEASE_METHOD_HANDLE;

  static {
    GET_ACQUIRE_METHOD_HANDLE = makeMethodHandle("getAcquire", GET_METHOD_TYPE, "get");
    SET_RELEASE_METHOD_HANDLE = makeMethodHandle("setRelease", SET_METHOD_TYPE, "fauxSetRelease");
  }

  private static MethodHandle makeMethodHandle(String name, MethodType type, String fallback) {
    try {
      return LOOKUP.findVirtual(AtomicBooleanCompat.class, name, type);
    } catch (NoSuchMethodException e) {
      try {
        return LOOKUP.findVirtual(AtomicBooleanCompat.class, fallback, type);
      } catch (Throwable t) {
        throw new ExceptionInInitializerError(t);
      }
    } catch (IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private boolean value;

  AtomicBooleanCompat(final boolean value) {
    super(value);
    this.value = value;
  }

  public boolean getAcquireCompat() throws Throwable {
    return (boolean) GET_ACQUIRE_METHOD_HANDLE.invokeExact(this);
  }

  public void setReleaseCompat(final boolean newValue) throws Throwable {
    SET_RELEASE_METHOD_HANDLE.invokeExact(this, newValue);
  }

  private void fauxSetRelease(final boolean newValue) {
    this.lazySet(newValue);
    this.value = newValue;
  }
}
