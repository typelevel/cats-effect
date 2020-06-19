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

/**
 * Holds platform-specific flags that control tracing behavior.
 *
 * The Scala compiler inserts a volatile bitmap access for module field accesses.
 * Because the `tracingMode` flag is read in various IO combinators, we are opting
 * to define it in a Java source file to avoid the volatile access.
 *
 * INTERNAL API.
 */
final class TracingPlatform {

    /**
     * A string flag that sets a global tracing mode for a JVM process.
     * Acceptable values are: DISABLED, RABBIT, SLUG.
     */
    public static final String tracingMode = Optional.ofNullable(System.getProperty("cats.effect.tracing.mode"))
            .filter(x -> !x.isEmpty())
            .orElse("disabled");

    /**
     * The number of trace lines to retain during tracing. If more trace
     * lines are produced, then the oldest trace lines will be discarded.
     * Automatically rounded up to the nearest power of 2.
     */
    public static final int maxTraceDepth = Optional.ofNullable(System.getProperty("cats.effect.tracing.maxTraceDepth"))
        .filter(x -> !x.isEmpty())
        .flatMap(x -> {
            try {
                return Optional.of(Integer.valueOf(x));
            } catch (Exception e) {
                return Optional.empty();
            }
        })
        .orElse(64);

}
