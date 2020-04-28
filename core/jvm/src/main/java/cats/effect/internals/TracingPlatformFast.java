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
 * Scala companion object field accesses cost a volatile read.
 * Since this flag is read at the construction of IO nodes,
 * we are opting to source this flag from a Java class to
 * bypass the volatile read and squeeze out as much performance
 * as possible.
 */
public class TracingPlatformFast {

    /**
     * A boolean flag that controls tracing for a JVM process.
     */
    public static final boolean tracingEnabled = Optional.ofNullable(System.getProperty("cats.effect.tracing.enabled"))
        .filter(x -> !x.isEmpty())
        .map(x -> Boolean.valueOf(x))
        .orElse(false);

}
