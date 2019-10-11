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

package cats.effect.internals

import cats.effect.IO

/**
 * INTERNAL API — Newtype encoding, used for defining `IO.Par`.
 *
 * The `IONewtype` abstract class indirection is needed for Scala 2.10,
 * otherwise we could just define these types straight on the
 * `IO.Par` companion object. In Scala 2.10 defining these types
 * straight on the companion object yields an error like
 * ''"only classes can have declared but undefined members"''.
 *
 * Inspired by
 * [[https://github.com/alexknvl/newtypes alexknvl/newtypes]].
 */
abstract private[effect] class IONewtype { self =>
  type Base
  trait Tag extends Any
  type Type[+A] <: Base with Tag

  def apply[A](fa: IO[A]): Type[A] =
    fa.asInstanceOf[Type[A]]

  def unwrap[A](fa: Type[A]): IO[A] =
    fa.asInstanceOf[IO[A]]
}
