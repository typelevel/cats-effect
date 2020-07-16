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

  type Bracket[F[_], E] = cekernel.Bracket[F, E]
  val Bracket = cekernel.Bracket

  type Region[R[_[_], _], F[_], E] = cekernel.Region[R, F, E]
  val Region = cekernel.Region

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

  type Managed[R[_[_], _], F[_]] = cekernel.Managed[R, F]
  val Managed = cekernel.Managed

  type Effect[F[_]] = cekernel.Effect[F]
  val Effect = cekernel.Effect

  type BracketThrow[F[_]] = cekernel.BracketThrow[F]
  type RegionThrow[R[_[_], _], F[_]] = cekernel.RegionThrow[R, F]

  type ConcurrentThrow[F[_]] = cekernel.ConcurrentThrow[F]

  type ConcurrentBracket[F[_], E] = cekernel.ConcurrentBracket[F, E]
  val ConcurrentBracket = cekernel.ConcurrentBracket

  type ConcurrentRegion[R[_[_], _], F[_], E] = cekernel.ConcurrentRegion[R, F, E]
  val ConcurrentRegion = cekernel.ConcurrentRegion

  type TemporalThrow[F[_]] = cekernel.TemporalThrow[F]

  type TemporalBracket[F[_], E] = cekernel.TemporalBracket[F, E]
  val TemporalBracket = cekernel.TemporalBracket

  type TemporalRegion[R[_[_], _], F[_], E] = cekernel.TemporalRegion[R, F, E]
  val TemporalRegion = cekernel.TemporalRegion

  type AsyncBracket[F[_]] = cekernel.AsyncBracket[F]
  val AsyncBracket = cekernel.AsyncBracket

  type AsyncRegion[R[_[_], _], F[_]] = cekernel.AsyncRegion[R, F]
  val AsyncRegion = cekernel.AsyncRegion

  type ParallelF[F[_], A] = cekernel.Par.ParallelF[F, A]
  val ParallelF = cekernel.Par.ParallelF
}
