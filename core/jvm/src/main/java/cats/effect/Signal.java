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

import java.util.function.Consumer;

final class Signal {

  private static final SignalHandler SIGNAL_HANDLER = new SunMiscSignalHandler();

  static void handleSignal(String signal, Consumer<Object> handler)
      throws IllegalArgumentException {
    SIGNAL_HANDLER.handleSignal(signal, handler);
  }

  abstract static class SignalHandler {
    abstract void handleSignal(String signal, Consumer<Object> handler)
        throws IllegalArgumentException;
  }

  static final class SunMiscSignalHandler extends SignalHandler {
    void handleSignal(String signal, Consumer<Object> handler) throws IllegalArgumentException {
      sun.misc.Signal.handle(new sun.misc.Signal(signal), handler::accept);
    }
  }

  static final class NoOpSignalHandler extends SignalHandler {
    void handleSignal(String signal, Consumer<Object> handler) throws IllegalArgumentException {}
  }
}
