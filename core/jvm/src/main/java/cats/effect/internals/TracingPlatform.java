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

    static {
        tracingEnabled = Optional.ofNullable(System.getProperty("cats.effect.tracing.enabled"))
            .filter(x -> !x.isEmpty())
            .map(x -> Boolean.valueOf(x))
            .orElse(true);
    }

}
