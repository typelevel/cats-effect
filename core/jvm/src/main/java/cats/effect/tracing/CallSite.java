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
import java.util.stream.Stream;

class CallSite {

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static final Class<?> STACK_WALKER_CLASS = findClass("java.lang.StackWalker",
      "cats.effect.tracing.StackWalkerCompat");
  private static final MethodType WALK_METHOD_TYPE = MethodType.methodType(Object.class, Function.class);
  private static final MethodHandle WALK_METHOD_HANDLE = createVirtualMethodHandle(STACK_WALKER_CLASS, "walk",
      WALK_METHOD_TYPE);

  private static final Object STACK_WALKER = initStackWalker();

  private static final MethodType GET_STRING_METHOD_TYPE = MethodType.methodType(String.class);
  private static final MethodType GET_INT_METHOD_TYPE = MethodType.methodType(int.class);
  private static final Class<?> STACK_FRAME_CLASS = findClass("java.lang.StackWalker.StackFrame",
      "java.lang.StackTraceElement");
  private static final MethodHandle GET_CLASS_NAME_METHOD_HANDLE = createVirtualMethodHandle(STACK_FRAME_CLASS,
      "getClassName", GET_STRING_METHOD_TYPE);
  private static final MethodHandle GET_METHOD_NAME_METHOD_HANDLE = createVirtualMethodHandle(STACK_FRAME_CLASS,
      "getMethodName", GET_STRING_METHOD_TYPE);
  private static final MethodHandle GET_FILE_NAME_METHOD_HANDLE = createVirtualMethodHandle(STACK_FRAME_CLASS,
      "getFileName", GET_STRING_METHOD_TYPE);
  private static final MethodHandle GET_LINE_NUMBER_METHOD_HANDLE = createVirtualMethodHandle(STACK_FRAME_CLASS,
      "getLineNumber", GET_INT_METHOD_TYPE);

  private static Object initStackWalker() {
    try {
      final MethodType getInstanceMethodType = MethodType.methodType(STACK_WALKER_CLASS);
      final MethodHandle getInstanceMethodHandle = createStaticMethodHandle(STACK_WALKER_CLASS, "getInstance",
          getInstanceMethodType);
      return getInstanceMethodHandle.invoke();
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }

  private static Class<?> findClass(String className, String fallbackClassName) {
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException e1) {
      try {
        return Class.forName(fallbackClassName);
      } catch (ClassNotFoundException e2) {
        throw new ExceptionInInitializerError(e2);
      }
    }
  }

  private static MethodHandle createStaticMethodHandle(Class<?> cls, String name, MethodType mt) {
    try {
      return LOOKUP.findStatic(cls, name, mt);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static MethodHandle createVirtualMethodHandle(Class<?> cls, String name, MethodType mt) {
    try {
      return LOOKUP.findVirtual(cls, name, mt);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static final Function<Stream<Object>, Object[]> collectTrace = s -> s.toArray(Object[]::new);

  private static final String[] runLoopFilter = new String[0];

  private static final String[] stackTraceFilter = new String[0];
}
