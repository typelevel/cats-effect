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

package cats

import cats.effect.{kernel => cekernel}

package object effect {

  type Outcome[F[_], E, A] = cekernel.Outcome[F, E, A]
  val Outcome = cekernel.Outcome

  type MonadCancel[F[_], E] = cekernel.MonadCancel[F, E]
  val MonadCancel = cekernel.MonadCancel

  type GenSpawn[F[_], E] = cekernel.GenSpawn[F, E]
  val GenSpawn = cekernel.GenSpawn

  type Fiber[F[_], E, A] = cekernel.Fiber[F, E, A]
  type Poll[F[_]] = cekernel.Poll[F]
  type Cont[F[_], K, R] = cekernel.Cont[F, K, R]

  type GenConcurrent[F[_], E] = cekernel.GenConcurrent[F, E]
  val GenConcurrent = cekernel.GenConcurrent

  type Clock[F[_]] = cekernel.Clock[F]
  val Clock = cekernel.Clock

  type GenTemporal[F[_], E] = cekernel.GenTemporal[F, E]
  val GenTemporal = cekernel.GenTemporal

  type Unique[F[_]] = cekernel.Unique[F]
  val Unique = cekernel.Unique

  type Sync[F[_]] = cekernel.Sync[F]
  val Sync = cekernel.Sync

  type Async[F[_]] = cekernel.Async[F]
  val Async = cekernel.Async

  type MonadCancelThrow[F[_]] = cekernel.MonadCancelThrow[F]
  val MonadCancelThrow = cekernel.MonadCancelThrow

  type Spawn[F[_]] = cekernel.Spawn[F]
  val Spawn = cekernel.Spawn

  type Concurrent[F[_]] = cekernel.Concurrent[F]
  val Concurrent = cekernel.Concurrent

  type Temporal[F[_]] = cekernel.Temporal[F]
  val Temporal = cekernel.Temporal

  type ParallelF[F[_], A] = cekernel.Par.ParallelF[F, A]
  val ParallelF = cekernel.Par.ParallelF

  type Resource[F[_], +A] = cekernel.Resource[F, A]
  val Resource = cekernel.Resource

  type OutcomeIO[A] = Outcome[IO, Throwable, A]
  type FiberIO[A] = Fiber[IO, Throwable, A]
  type ResourceIO[A] = Resource[IO, A]

  type Deferred[F[_], A] = cekernel.Deferred[F, A]
  val Deferred = cekernel.Deferred

  type Ref[F[_], A] = cekernel.Ref[F, A]
  val Ref = cekernel.Ref

  private[effect] type IOLocalState = scala.collection.immutable.Map[IOLocal[_], Any]
  private[effect] object IOLocalState {
    val empty: IOLocalState = scala.collection.immutable.Map.empty
  }
}
