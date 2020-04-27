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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 *
 * Motivation:
 * In Scala, object-level variable reads cause a volatile read.
 * Instead,
 */
class TracingPlatform {

    /**
     * A boolean variable that controls tracing globally. For tracing to
     * take effect, this flag must be enabled.
     */
    public static final boolean tracingEnabled;

    /**
     * Global, thread-safe cache for traces. Keys are generally
     * lambda references.
     *
     * TODO: Could this be a thread-local?
     * If every thread eventually calculates its own set,
     * there should be no issue?
     *
     * TODO: Bound the cache.
     */
    public static final Map<Object, Object> traceCache;

    static {
        tracingEnabled = Optional.ofNullable(System.getProperty("cats.effect.tracingEnabled"))
                .filter(x -> !x.isEmpty())
                .map(x -> Boolean.valueOf(x)) // TODO: this can throw
                .orElse(false);

        traceCache = new HashMap<>();
    }

}
