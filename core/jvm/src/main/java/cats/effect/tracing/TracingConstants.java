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

package cats.effect.tracing;

import java.util.Optional;

/**
 * Holds platform-specific flags that control tracing behavior.
 *
 * <p>The Scala compiler inserts a volatile bitmap access for module field accesses. Because the
 * `tracingMode` flag is read in various IO constructors, we are opting to define it in a Java
 * source file to avoid the volatile access.
 *
 * <p>INTERNAL API with no source or binary compatibility guarantees.
 */
public final class TracingConstants {

  private TracingConstants() {}

  /**
   * Sets stack tracing mode for a JVM process, which controls how much stack trace information is
   * captured. Acceptable values are: NONE, CACHED, FULL.
   */
  private static final String stackTracingMode =
      Optional.ofNullable(System.getProperty("cats.effect.tracing.mode"))
          .filter(x -> !x.isEmpty())
          .orElse("cached");

  static final boolean isCachedStackTracing = stackTracingMode.equalsIgnoreCase("cached");

  static final boolean isFullStackTracing = stackTracingMode.equalsIgnoreCase("full");

  public static final boolean isStackTracing = isFullStackTracing || isCachedStackTracing;
}
