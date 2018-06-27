/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

import cats.Eval

private[effect] trait ExecNewtype { self =>
  type Base
  trait Tag extends Any
  type Type[+A] <: Base with Tag

  private[effect] def create[A](fa: Eval[A]): Type[A] =
    fa.asInstanceOf[Type[A]]

  private[effect] def unwrap[A](fa: Type[A]): Eval[A] =
    fa.asInstanceOf[Eval[A]]
}
