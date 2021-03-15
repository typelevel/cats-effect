/*
 * Copyright 2020-2021 Typelevel
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

import java.util.concurrent.atomic.AtomicReference

// TODO rename
// `result` is published by a volatile store on the atomic integer extended
// by this class.
private final class ContState(var wasFinalizing: Boolean)
    extends AtomicReference[ContState.Phase](ContState.Initial)

object ContState {
  sealed abstract class Phase
  case object Initial extends Phase
  case object Waiting extends Phase
  final case class Result(result: Either[Throwable, Any]) extends Phase
}
