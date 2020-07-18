/*
 * Copyright 2020 Typelevel
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

import cats.effect.{kernel => cekernel}

package object effect {

  type Outcome[F[_], E, A] = cekernel.Outcome[F, E, A]
  val Outcome = cekernel.Outcome

  type Concurrent[F[_], E] = cekernel.Concurrent[F, E]
  val Concurrent = cekernel.Concurrent

  type Fiber[F[_], E, A] = cekernel.Fiber[F, E, A]

  type Clock[F[_]] = cekernel.Clock[F]
  val Clock = cekernel.Clock

  type Temporal[F[_], E] = cekernel.Temporal[F, E]
  val Temporal = cekernel.Temporal

  type Sync[F[_]] = cekernel.Sync[F]
  val Sync = cekernel.Sync

  type SyncEffect[F[_]] = cekernel.SyncEffect[F]
  val SyncEffect = cekernel.SyncEffect

  type Async[F[_]] = cekernel.Async[F]
  val Async = cekernel.Async

  type Effect[F[_]] = cekernel.Effect[F]
  val Effect = cekernel.Effect

  type ConcurrentThrow[F[_]] = cekernel.ConcurrentThrow[F]
  type TemporalThrow[F[_]] = cekernel.TemporalThrow[F]

  type ParallelF[F[_], A] = cekernel.Par.ParallelF[F, A]
  val ParallelF = cekernel.Par.ParallelF
}
