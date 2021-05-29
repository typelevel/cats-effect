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

package cats.effect.tracing;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

class CallSite {

  private static final Object STACK_WALKER = initStackWalker();

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

  private static Object initStackWalker() {
    try {
      final Class<?> stackWalkerClass = findStackWalkerClass();
      final MethodType getInstanceMethodType = MethodType.methodType(stackWalkerClass);
      final MethodHandle getInstanceMethodHandle = createGetInstanceMethodHandle(stackWalkerClass,
          getInstanceMethodType);
      return getInstanceMethodHandle.invoke();
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  private static Class<?> findStackWalkerClass() {
    try {
      return Class.forName("java.lang.StackWalker");
    } catch (ClassNotFoundException e) {
      return StackWalkerCompat.class;
    }
  }

  private static MethodHandle createGetInstanceMethodHandle(Class<?> stackWalkerClass,
      MethodType getInstanceMethodType) {
    try {
      return LOOKUP.findStatic(stackWalkerClass, "getInstance", getInstanceMethodType);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
