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

package cats

package object effect {

  /**
    * Exec is a data type that allows for suspending synchronous computations.
    * It can be seen as the synchronous non-concurrent counterpart to `IO`.
    * However, it is important realize, that unlike `IO`, `Exec` does not catch any errors
    * and in order to form a valid `Sync` needs to be combined with `EitherT`.
    */
  type Exec[+A] = Exec.Type[A]
  val Exec = ExecImpl
}
