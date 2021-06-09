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

import java.util.Optional;

class TracingConstants {

  /**
   * Sets stack tracing mode for a JVM process, which controls how much stack
   * trace information is captured. Acceptable values are: NONE, CACHED, FULL.
   */
  private static final String stackTracingMode = Optional.ofNullable(System.getProperty("cats.effect.stackTracingMode"))
      .filter(x -> !x.isEmpty()).orElse("cached");

  static final boolean isCachedStackTracing = stackTracingMode.equalsIgnoreCase("cached");

  static final boolean isFullStackTracing = stackTracingMode.equalsIgnoreCase("full");

  static final boolean isStackTracing = isFullStackTracing || isCachedStackTracing;

  /**
   * The number of trace lines to retain during tracing. If more trace lines are
   * produced, then the oldest trace lines will be discarded. Automatically
   * rounded up to the nearest power of 2.
   */
  static final int traceBufferLogSize = Optional.ofNullable(System.getProperty("cats.effect.traceBufferLogSize"))
      .filter(x -> !x.isEmpty()).flatMap(x -> {
        try {
          return Optional.of(Integer.valueOf(x));
        } catch (Exception e) {
          return Optional.empty();
        }
      }).orElse(4);

  /**
   * Sets the enhanced exceptions flag, which controls whether or not the stack
   * traces of IO exceptions are augmented to include async stack trace
   * information. Stack tracing must be enabled in order to use this feature. This
   * flag is enabled by default.
   */
  static final boolean enhancedExceptions = Optional.ofNullable(System.getProperty("cats.effect.enhancedExceptions"))
      .map(Boolean::valueOf).orElse(true);
}
