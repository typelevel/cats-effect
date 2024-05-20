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

package cats.effect.std.syntax

import cats.effect.kernel.Fiber
import cats.effect.std.Supervisor

trait SupervisorSyntax {
  implicit def supervisorOps[F[_], A](wrapped: F[A]): SupervisorOps[F, A] =
    new SupervisorOps(wrapped)
}

final class SupervisorOps[F[_], A] private[syntax] (private[syntax] val wrapped: F[A])
    extends AnyVal {
  def supervise(supervisor: Supervisor[F]): F[Fiber[F, Throwable, A]] =
    supervisor.supervise(wrapped)
}
