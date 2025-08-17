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

package cats.effect.std

import cats._
import cats.data.State
import cats.effect._
import cats.implicits._

import scala.concurrent.duration._

class MapRefJVMSuite extends BaseSuite {

  private val smallDelay: IO[Unit] = IO.sleep(20.millis)
  private def awaitEqual[A: Eq](t: IO[A], success: A): IO[Unit] =
    t.flatMap(a => if (Eq[A].eqv(a, success)) IO.unit else smallDelay *> awaitEqual(t, success))

  real("MapRef.ofScalaConcurrentTrieMap - concurrent modifications") {
    val finalValue = 100
    val r = MapRef.inScalaConcurrentTrieMap[SyncIO, IO, Unit, Int].unsafeRunSync()
    val modifies = List.fill(finalValue)(r(()).update(_.map(_ + 1))).parSequence
    r(()).set(Some(0)) *> modifies.start *> awaitEqual(r(()).get, finalValue.some)
  }

  real("MapRef.ofScalaConcurrentTrieMap - getAndSet - successful") {
    for {
      r <- MapRef.ofScalaConcurrentTrieMap[IO, Unit, Int]
      _ <- r(()).set(Some(0))
      getAndSetResult <- r(()).getAndSet(Some(1))
      getResult <- r(()).get
    } yield {
      assertEquals(getAndSetResult, Some(0))
      assertEquals(getResult, Some(1))
    }
  }

  real("MapRef.ofScalaConcurrentTrieMap - access - successful") {
    for {
      r <- MapRef.ofScalaConcurrentTrieMap[IO, Unit, Int]
      _ <- r(()).set(Some(0))
      accessed <- r(()).access
      (value, setter) = accessed
      success <- setter(value.map(_ + 1))
      result <- r(()).get
    } yield {
      assert(success)
      assertEquals(result, Some(1))
    }
  }

  real(
    "MapRef.ofScalaConcurrentTrieMap - access - setter should fail if value is modified before setter is called with None/Some") {
    for {
      r <- MapRef.ofScalaConcurrentTrieMap[IO, Unit, Int]
      accessed <- r(()).access
      (value, setter) = accessed
      _ <- r(()).set(Some(5))
      success <- setter(value.map(_ + 1))
      result <- r(()).get
    } yield {
      assert(!success)
      assertEquals(result, Some(5))
    }
  }

  real(
    "MapRef.ofScalaConcurrentTrieMap - access - setter should fail if value is modified before setter is called with init Some/Some") {
    for {
      r <- MapRef.ofScalaConcurrentTrieMap[IO, Unit, Int]
      _ <- r(()).set(Some(0))
      accessed <- r(()).access
      (value, setter) = accessed
      _ <- r(()).set(Some(5))
      success <- setter(value.map(_ + 1))
      result <- r(()).get
    } yield {
      assert(!success)
      assertEquals(result, Some(5))
    }
  }

  real(
    "MapRef.ofScalaConcurrentTrieMap - access - setter should fail if value is modified before setter is called with init Some/None") {
    for {
      r <- MapRef.ofScalaConcurrentTrieMap[IO, Unit, Int]
      _ <- r(()).set(Some(0))
      accessed <- r(()).access
      (value, setter) = accessed
      _ <- r(()).set(Some(5))
      success <- setter(None)
      result <- r(()).get
    } yield {
      assert(!success)
      assertEquals(result, Some(5))
    }
  }

  real("MapRef.ofScalaConcurrentTrieMap - tryUpdate - modification occurs successfully") {
    for {
      r <- MapRef.ofScalaConcurrentTrieMap[IO, Unit, Int]
      _ <- r(()).set(Some(0))
      result <- r(()).tryUpdate(_.map(_ + 1))
      value <- r(()).get
    } yield {
      assert(result)
      assertEquals(value, Some(1))
    }
  }

  real(
    "MapRef.ofScalaConcurrentTrieMap - tryUpdate - should fail to update if modification has occurred") {
    import cats.effect.unsafe.implicits.global
    val updateRefUnsafely: Ref[IO, Option[Int]] => Unit =
      _.update(_.map(_ + 1)).unsafeRunSync()

    for {
      r <- MapRef.ofScalaConcurrentTrieMap[IO, Unit, Int]
      _ <- r(()).set(Some(0))
      result <- r(()).tryUpdate(currentValue => {
        updateRefUnsafely(r(()))
        currentValue.map(_ + 1)
      })
    } yield assert(!result)
  }

  real("MapRef.ofScalaConcurrentTrieMap - tryModifyState - modification occurs successfully") {
    for {
      r <- MapRef.ofScalaConcurrentTrieMap[IO, Unit, Int]
      _ <- r(()).set(Some(0))
      result <- r(()).tryModifyState(State.pure(Some(1)))
    } yield assertEquals(result, Option(Some(1)))
  }

  real("MapRef.ofScalaConcurrentTrieMap - modifyState - modification occurs successfully") {
    for {
      r <- MapRef.ofScalaConcurrentTrieMap[IO, Unit, Int]
      _ <- r(()).set(Some(0))
      result <- r(()).modifyState(State.pure(Some(1)))
    } yield assertEquals(result, Some(1))
  }

  // Requires unsafeRunSync so doesn't work with JS
  real(
    "MapRef.ofSingleImmutableMapRef - tryUpdate - should fail to update if modification has occurred") {
    import cats.effect.unsafe.implicits.global
    val updateRefUnsafely: Ref[IO, Option[Int]] => Unit =
      _.update(_.map(_ + 1)).unsafeRunSync()

    for {
      r <- MapRef.ofSingleImmutableMap[IO, Unit, Int]()
      _ <- r(()).set(Some(0))
      result <- r(()).tryUpdate(currentValue => {
        updateRefUnsafely(r(()))
        currentValue.map(_ + 1)
      })
    } yield assert(!result)
  }

  real(
    "MapRef.ofSingleImmutableMapRef - tryUpdate - should fail to update if modification has occurred") {
    import cats.effect.unsafe.implicits.global
    val updateRefUnsafely: Ref[IO, Option[Int]] => Unit =
      _.update(_.map(_ + 1)).unsafeRunSync()

    for {
      r <- MapRef.ofConcurrentHashMap[IO, Unit, Int]()
      _ <- r(()).set(Some(0))
      result <- r(()).tryUpdate(currentValue => {
        updateRefUnsafely(r(()))
        currentValue.map(_ + 1)
      })
    } yield assert(!result)
  }

}
