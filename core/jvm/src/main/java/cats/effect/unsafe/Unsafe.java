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

package cats.effect.unsafe;

import java.lang.reflect.Field;

final class Unsafe {
  private static final sun.misc.Unsafe UNSAFE;

  static {
    UNSAFE = init();
  }

  static void acquireFence() {
    UNSAFE.loadFence();
  }

  static boolean compareAndSwapInt(Object object, long offset, int expected, int value) {
    return UNSAFE.compareAndSwapInt(object, offset, expected, value);
  }

  static void putOrderedInt(Object object, long offset, int value) {
    UNSAFE.putOrderedInt(object, offset, value);
  }

  static long objectFieldOffset(Field field) {
    return UNSAFE.objectFieldOffset(field);
  }

  private static sun.misc.Unsafe init() {
    try {
      final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
      for (Field field : unsafeClass.getDeclaredFields()) {
        if (field.getType() == unsafeClass) {
          field.setAccessible(true);
          return (sun.misc.Unsafe) field.get(null);
        }
      }
    } catch (ClassNotFoundException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(new Exception("Failed to obtain a sun.misc.Unsafe instance", e));
    }
    throw new IllegalStateException("No instance of sun.misc.Unsafe found");
  }
}
