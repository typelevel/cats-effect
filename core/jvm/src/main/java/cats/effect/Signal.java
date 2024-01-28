/*
 * Copyright 2020-2024 Typelevel
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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

final class Signal {

  private Signal() {}

  private static final MethodHandle SIGNAL_CONSTRUCTOR_METHOD_HANDLE;
  private static final MethodHandle HANDLE_STATIC_METHOD_HANDLE;

  private static final SignalHandler SIGNAL_HANDLER;

  static {
    final Class<?> sunMiscSignalClass = findClass("sun.misc.Signal");
    final Class<?> sunMiscSignalHandlerClass = findClass("sun.misc.SignalHandler");

    final boolean classesAvailable =
        (sunMiscSignalClass != null) && (sunMiscSignalHandlerClass != null);

    if (classesAvailable) {
      final MethodHandles.Lookup lookup = MethodHandles.lookup();

      SIGNAL_CONSTRUCTOR_METHOD_HANDLE =
          initSignalConstructorMethodHandle(lookup, sunMiscSignalClass);
      HANDLE_STATIC_METHOD_HANDLE =
          initHandleStaticMethodHandle(lookup, sunMiscSignalClass, sunMiscSignalHandlerClass);

      SIGNAL_HANDLER = initSignalHandler(sunMiscSignalHandlerClass);
    } else {
      SIGNAL_CONSTRUCTOR_METHOD_HANDLE = null;
      HANDLE_STATIC_METHOD_HANDLE = null;

      // Gracefully degrade to a no-op implementation.
      SIGNAL_HANDLER =
          new SignalHandler() {
            final void handle(String signal, Consumer<Object> handler) {}
          };
    }
  }

  static void handle(String signal, Consumer<Object> handler) {
    SIGNAL_HANDLER.handle(signal, handler);
  }

  abstract static class SignalHandler {
    abstract void handle(String signal, Consumer<Object> handler);
  }

  private static final Class<?> findClass(String name) {
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  private static final MethodHandle initSignalConstructorMethodHandle(
      MethodHandles.Lookup lookup, Class<?> sunMiscSignalClass) {
    final MethodType signalConstructorMethodType = MethodType.methodType(void.class, String.class);
    try {
      return lookup.findConstructor(sunMiscSignalClass, signalConstructorMethodType);
    } catch (Exception e) {
      return null;
    }
  }

  private static final MethodHandle initHandleStaticMethodHandle(
      MethodHandles.Lookup lookup,
      Class<?> sunMiscSignalClass,
      Class<?> sunMiscSignalHandlerClass) {
    final MethodType handleStaticMethodType =
        MethodType.methodType(
            sunMiscSignalHandlerClass, sunMiscSignalClass, sunMiscSignalHandlerClass);
    try {
      return lookup.findStatic(sunMiscSignalClass, "handle", handleStaticMethodType);
    } catch (Exception e) {
      return null;
    }
  }

  private static final InvocationHandler invocationHandlerFromConsumer(Consumer<Object> consumer) {
    return (proxy, method, args) -> {
      consumer.accept(args[0]);
      return null;
    };
  }

  private static final SignalHandler initSignalHandler(Class<?> sunMiscSignalHandlerClass) {
    return new SignalHandler() {
      final void handle(String signal, Consumer<Object> handler) {
        final InvocationHandler invocationHandler = invocationHandlerFromConsumer(handler);
        final Object proxy =
            Proxy.newProxyInstance(
                Signal.class.getClassLoader(),
                new Class<?>[] {sunMiscSignalHandlerClass},
                invocationHandler);

        try {
          final Object s = SIGNAL_CONSTRUCTOR_METHOD_HANDLE.invoke(signal);
          HANDLE_STATIC_METHOD_HANDLE.invoke(s, proxy);
        } catch (Throwable t) {
          return; // Gracefully degrade to a no-op.
        }
      }
    };
  }
}
