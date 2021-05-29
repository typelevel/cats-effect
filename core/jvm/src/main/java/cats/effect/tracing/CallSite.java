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
import java.util.function.Function;

class CallSite {

  private static final Object STACK_WALKER = initStackWalker();

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static final Class<?> STACK_WALKER_CLASS = findStackWalkerClass();
  private static final MethodType WALK_METHOD_TYPE = MethodType.methodType(Object.class, Function.class);
  private static final MethodHandle WALK_METHODHANDLE = createWalkMethodHandle();

  private static Object initStackWalker() {
    try {
      final MethodType getInstanceMethodType = MethodType.methodType(STACK_WALKER_CLASS);
      final MethodHandle getInstanceMethodHandle = createGetInstanceMethodHandle(getInstanceMethodType);
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

  private static MethodHandle createGetInstanceMethodHandle(MethodType getInstanceMethodType) {
    try {
      return LOOKUP.findStatic(STACK_WALKER_CLASS, "getInstance", getInstanceMethodType);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static MethodHandle createWalkMethodHandle() {
    try {
      return LOOKUP.findVirtual(STACK_WALKER_CLASS, "walk", WALK_METHOD_TYPE);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
