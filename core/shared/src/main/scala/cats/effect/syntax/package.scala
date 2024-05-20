/*
 * Copyright 2020-2024 Typelevel
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

package object syntax {

  object all extends AllSyntax

  object monadCancel extends kernel.syntax.MonadCancelSyntax
  object spawn extends kernel.syntax.GenSpawnSyntax
  object concurrent extends kernel.syntax.GenConcurrentSyntax
  object temporal extends kernel.syntax.GenTemporalSyntax
  object async extends kernel.syntax.AsyncSyntax
  object resource extends kernel.syntax.ResourceSyntax
  object clock extends kernel.syntax.ClockSyntax

  object supervisor extends std.syntax.SupervisorSyntax
  object dispatcher extends DispatcherSyntax
}
