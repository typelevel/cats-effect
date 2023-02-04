/*
 * Copyright 2020-2023 Typelevel
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

package cats.effect

/**
 * A utility to convert a by-name `thunk: => A` to a `Function0[A]` (its binary representation).
 * Scala 2 performs this optimization automatically but on Scala 3 the thunk is wrapped inside
 * of a new `Function0`. See https://github.com/typelevel/cats-effect/pull/2226
 */
private object Thunk {
  private[this] val impl =
    ((x: Any) => x).asInstanceOf[(=> Any) => Function0[Any]]

  def asFunction0[A](thunk: => A): Function0[A] = impl(thunk).asInstanceOf[Function0[A]]
}
