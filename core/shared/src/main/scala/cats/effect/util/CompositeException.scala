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

package cats.effect.util

import cats.data.NonEmptyList

/** A composite exception represents a list of exceptions
 * caught from evaluating multiple independent actions
 * and that need to be signaled together.
 *
 * Note the constructor doesn't allow wrapping anything less
 * than two throwable references.
 *
 * Use [[cats.effect.util.CompositeException.apply apply]]
 * for building composite exceptions.
 */
final class CompositeException(val head: Throwable, val tail: NonEmptyList[Throwable])
    extends RuntimeException(
      s"Multiple exceptions were thrown (${1 + tail.size}), first ${head.getClass.getName}: ${head.getMessage}"
    )
    with Serializable {

  /** Returns the set of all errors wrapped by this composite. */
  def all: NonEmptyList[Throwable] =
    head :: tail
}

object CompositeException {

  /** Simple builder for [[CompositeException]]. */
  def apply(first: Throwable, second: Throwable, rest: List[Throwable] = Nil): CompositeException =
    new CompositeException(first, NonEmptyList(second, rest))

  /** For easy pattern matching a `CompositeException`. */
  def unapplySeq(ref: CompositeException): Option[Seq[Throwable]] =
    Some(ref.head :: ref.tail.toList)
}
