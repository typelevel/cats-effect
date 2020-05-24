/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats.effect.internals;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scala object field accesses cost a volatile read across modules.
 * Since this flag is read during construction of IO nodes, we are opting to
 * hold this flag in a Java class to bypass the volatile read.
 */
public final class TracingPlatformFast {

    /**
     * A boolean flag that enables or disables tracing for a JVM process.
     * Since it is declared static and final, the JIT compiler has the liberty
     * to completely eliminate code paths consequent to the conditional.
     */
    public static final boolean isTracingEnabled = Optional.ofNullable(System.getProperty("cats.effect.tracing.enabled"))
        .filter(x -> !x.isEmpty())
        .map(x -> Boolean.valueOf(x))
        .orElse(true);

    /**
     * The number of trace lines to retain during tracing. If more trace
     * lines are produced, then the oldest trace lines will be discarded.
     * Automatically rounded up to the nearest power of 2.
     */
    public static final int maxTraceFrameSize = Optional.ofNullable(System.getProperty("cats.effect.tracing.maxTraceFrameSize"))
        .filter(x -> !x.isEmpty())
        .flatMap(x -> {
            try {
                return Optional.of(Integer.valueOf(x));
            } catch (Exception e) {
                return Optional.empty();
            }
        })
        .orElse(512);

    /**
     * Cache for trace frames. Keys are references to:
     * - lambda classes
     */
    public static final ConcurrentHashMap<Class<?>, Object> frameCache = new ConcurrentHashMap<>();

    public static final ThreadLocal<Integer> localTracingMode = ThreadLocal.withInitial(() -> 1);

}
