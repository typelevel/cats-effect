/*
 * Copyright 2020-2025 Typelevel
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

package cats.effect.kernel

import cats.data._
import cats.effect.{BaseSuite, IO, SyncIO}

import scala.reflect.ClassTag

class DerivationRefinementSuite extends BaseSuite {

  type AsyncStack[F[_], A] = Kleisli[OptionT[EitherT[IorT[F, Int, *], String, *], *], Unit, A]
  type SyncStack[F[_], A] = StateT[ReaderWriterStateT[F, String, Int, Unit, *], Boolean, A]

  test("returns Async for OptionT at runtime if possible") {
    check[IO, OptionT, Sync, Async]
    check[IO, OptionT, Temporal, Async]
    check[IO, OptionT, Concurrent, Async]
    check[IO, OptionT, Spawn, Async]
    check[IO, OptionT, MonadCancelThrow, Async]
  }

  test("returns Async for EitherT at runtime if possible") {
    type EitherTString[F[_], A] = EitherT[F, String, A]
    check[IO, EitherTString, Sync, Async]
    check[IO, EitherTString, Temporal, Async]
    check[IO, EitherTString, Concurrent, Async]
    check[IO, EitherTString, Spawn, Async]
    check[IO, EitherTString, MonadCancelThrow, Async]
  }

  test("returns Async for Kleisli at runtime if possible") {
    type StringKleisli[F[_], A] = Kleisli[F, String, A]
    check[IO, StringKleisli, Sync, Async]
    check[IO, StringKleisli, Temporal, Async]
    check[IO, StringKleisli, Concurrent, Async]
    check[IO, StringKleisli, Spawn, Async]
    check[IO, StringKleisli, MonadCancelThrow, Async]
  }

  test("returns Async for IorT at runtime if possible") {
    type StringIorT[F[_], A] = IorT[F, String, A]
    check[IO, StringIorT, Sync, Async]
    check[IO, StringIorT, Temporal, Async]
    check[IO, StringIorT, Concurrent, Async]
    check[IO, StringIorT, Spawn, Async]
    check[IO, StringIorT, MonadCancelThrow, Async]
  }

  test("returns Async for WriterT at runtime if possible") {
    type StringWriterT[F[_], A] = WriterT[F, String, A]
    check[IO, StringWriterT, Sync, Async]
    check[IO, StringWriterT, Temporal, Async]
    check[IO, StringWriterT, Concurrent, Async]
    check[IO, StringWriterT, Spawn, Async]
    check[IO, StringWriterT, MonadCancelThrow, Async]
  }

  test("returns Sync for StateT at runtime if possible") {
    type StringStateT[F[_], A] = StateT[F, String, A]
    check[IO, StringStateT, MonadCancelThrow, Sync]
    check[SyncIO, StringStateT, MonadCancelThrow, Sync]
  }

  test("returns Sync for ReaderWriterStateT at runtime if possible") {
    type TestRWST[F[_], A] = ReaderWriterStateT[F, String, Int, Unit, A]
    check[IO, TestRWST, MonadCancelThrow, Sync]
    check[SyncIO, TestRWST, MonadCancelThrow, Sync]
  }

  test("returns Async for stacked transformers at runtime if possible") {
    check[IO, AsyncStack, Sync, Async]
    check[IO, AsyncStack, Temporal, Async]
    check[IO, AsyncStack, Concurrent, Async]
    check[IO, AsyncStack, Spawn, Async]
    check[IO, AsyncStack, MonadCancelThrow, Async]
  }

  test("returns Sync for stacked transformers at runtime if possible") {
    check[IO, SyncStack, MonadCancelThrow, Sync]
    check[SyncIO, SyncStack, MonadCancelThrow, Sync]
    check[SyncIO, AsyncStack, MonadCancelThrow, Sync]
  }

  // read as: for base effect F, ensure the T instance for monad transformer M is actually of its subtype R
  def check[F[_], M[_[_], *], T[a[_]] <: MonadCancel[a, Throwable], R[a[_]] <: T[a]](
      implicit T: T[M[F, *]],
      ct: ClassTag[R[F]]) =
    assert(ct.runtimeClass.isAssignableFrom(T.getClass))

}
