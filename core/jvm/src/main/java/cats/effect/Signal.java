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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

final class Signal {

  private static final SignalHandler SIGNAL_HANDLER = initSignalHandler();

  static void handleSignal(String signal, Consumer<Object> handler) {
    SIGNAL_HANDLER.handleSignal(signal, handler);
  }

  abstract static class SignalHandler {
    abstract void handleSignal(String signal, Consumer<Object> handler);
  }

  static final SignalHandler initSignalHandler() {
    try {
      final Class<?> sunMiscSignalClass = Class.forName("sun.misc.Signal");
      final Class<?> sunMiscSignalHandlerClass = Class.forName("sun.misc.SignalHandler");
      final MethodHandles.Lookup lookup = MethodHandles.lookup();
      final MethodType signalConstructorMethodType =
          MethodType.methodType(void.class, String.class);
      final MethodHandle signalConstructorMethodHandle =
          lookup.findConstructor(sunMiscSignalClass, signalConstructorMethodType);
      final MethodType handleMethodType =
          MethodType.methodType(
              sunMiscSignalHandlerClass, sunMiscSignalClass, sunMiscSignalHandlerClass);
      final MethodHandle handleMethodHandle =
          lookup.findStatic(sunMiscSignalClass, "handle", handleMethodType);

      return new SignalHandler() {
        final void handleSignal(String signal, Consumer<Object> handler) {
          final InvocationHandler invocationHandler = invocationHandlerFromConsumer(handler);
          final Object proxy =
              Proxy.newProxyInstance(
                  Signal.class.getClassLoader(),
                  new Class<?>[] {sunMiscSignalHandlerClass},
                  invocationHandler);

          try {
            final Object s = signalConstructorMethodHandle.invoke(signal);
            handleMethodHandle.invoke(s, proxy);
          } catch (Throwable t) {
            // TODO: Maybe t.printStackTrace() ?
            return; // Gracefully degrade to a no-op.
          }
        }
      };
    } catch (Throwable t) {
      // TODO: Maybe t.printStackTrace() ?

      // Gracefully degrade to a no-op implementation.
      return new SignalHandler() {
        final void handleSignal(String signal, Consumer<Object> handler) {}
      };
    }
  }

  static final InvocationHandler invocationHandlerFromConsumer(Consumer<Object> consumer) {
    return (proxy, method, args) -> {
      if (method.getName().equals("handle")) {
        consumer.accept(args[0]);
        return null;
      }
      return null;
    };
  }
}
