/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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
package effect

import cats.effect.internals.IOPlatform
import cats.effect.laws.discipline.SyncEffectTests
import cats.effect.laws.discipline.arbitrary._
import cats.implicits._
import cats.kernel.laws.discipline.MonoidTests
import cats.laws._
import cats.laws.discipline._
import cats.laws.discipline.arbitrary._

class SyncIOTests extends BaseTestsSuite {
  checkAllAsync("SyncIO", _ => SyncEffectTests[SyncIO].syncEffect[Int, Int, Int])
  checkAllAsync("SyncIO", _ => MonoidTests[SyncIO[Int]].monoid)
  checkAllAsync("SyncIO", _ => SemigroupKTests[SyncIO].semigroupK[Int])
  checkAllAsync("SyncIO", _ => AlignTests[SyncIO].align[Int, Int, Int, Int])

  test("defer evaluation until run") {
    var run = false
    val ioa = SyncIO { run = true }
    run shouldEqual false
    ioa.unsafeRunSync()
    run shouldEqual true
  }

  test("catch exceptions within main block") {
    case object Foo extends Exception

    val ioa = SyncIO(throw Foo)

    ioa.attempt.unsafeRunSync() should matchPattern {
      case Left(Foo) => ()
    }
  }

  test("fromEither handles Throwable in Left Projection") {
    case object Foo extends Exception
    val e: Either[Throwable, Nothing] = Left(Foo)

    SyncIO.fromEither(e).attempt.unsafeRunSync() should matchPattern {
      case Left(Foo) => ()
    }
  }

  test("fromEither handles a Value in Right Projection") {
    case class Foo(x: Int)
    val e: Either[Throwable, Foo] = Right(Foo(1))

    SyncIO.fromEither(e).attempt.unsafeRunSync() should matchPattern {
      case Right(Foo(_)) => ()
    }
  }

  test("attempt flatMap loop") {
    def loop[A](source: SyncIO[A], n: Int): SyncIO[A] =
      source.attempt.flatMap {
        case Right(l) =>
          if (n <= 0) SyncIO.pure(l)
          else loop(source, n - 1)
        case Left(e) =>
          SyncIO.raiseError(e)
      }

    val value = loop(SyncIO("value"), 10000).unsafeRunSync()
    value shouldEqual "value"
  }

  test("attempt foldLeft sequence") {
    val count = 10000
    val loop = (0 until count).foldLeft(SyncIO(0)) { (acc, _) =>
      acc.attempt.flatMap {
        case Right(x) => SyncIO.pure(x + 1)
        case Left(e)  => SyncIO.raiseError(e)
      }
    }

    val value = loop.unsafeRunSync()
    value shouldEqual count
  }

  test("SyncIO(throw ex).attempt.map") {
    val dummy = new RuntimeException("dummy")
    val io = SyncIO[Int](throw dummy).attempt.map {
      case Left(`dummy`) => 100
      case _             => 0
    }

    val value = io.unsafeRunSync()
    value shouldEqual 100
  }

  test("SyncIO(throw ex).flatMap.attempt.map") {
    val dummy = new RuntimeException("dummy")
    val io = SyncIO[Int](throw dummy).flatMap(SyncIO.pure).attempt.map {
      case Left(`dummy`) => 100
      case _             => 0
    }

    val value = io.unsafeRunSync()
    value shouldEqual 100
  }

  test("SyncIO(throw ex).map.attempt.map") {
    val dummy = new RuntimeException("dummy")
    val io = SyncIO[Int](throw dummy).map(x => x).attempt.map {
      case Left(`dummy`) => 100
      case _             => 0
    }

    val value = io.unsafeRunSync()
    value shouldEqual 100
  }

  testAsync("io.to[IO] <-> io.toIO") { implicit ec =>
    check { (io: SyncIO[Int]) =>
      io.to[IO] <-> io.toIO
    }
  }

  testAsync("io.attempt.to[IO] <-> io.toIO.attempt") { implicit ec =>
    check { (io: SyncIO[Int]) =>
      io.attempt.to[IO] <-> io.toIO.attempt
    }
  }

  testAsync("io.handleError(f).to[IO] <-> io.handleError(f)") { implicit ec =>
    val F = implicitly[Sync[IO]]

    check { (io: IO[Int], f: Throwable => IO[Int]) =>
      val fa = F.handleErrorWith(io)(f)
      fa.to[IO] <-> fa
    }
  }

  test("suspend with unsafeRunSync") {
    val io = SyncIO.suspend(SyncIO(1)).map(_ + 1)
    io.unsafeRunSync() shouldEqual 2
  }

  test("map is stack-safe for unsafeRunSync") {
    import IOPlatform.{fusionMaxStackDepth => max}
    val f = (x: Int) => x + 1
    val io = (0 until (max * 10000)).foldLeft(SyncIO(0))((acc, _) => acc.map(f))

    io.unsafeRunSync() shouldEqual max * 10000
  }

  testAsync("IO#redeem(throw, f) <-> IO#map") { implicit ec =>
    check { (io: IO[Int], f: Int => Int) =>
      io.redeem(e => throw e, f) <-> io.map(f)
    }
  }

  testAsync("IO#redeem(f, identity) <-> IO#handleError") { implicit ec =>
    check { (io: IO[Int], f: Throwable => Int) =>
      io.redeem(f, identity) <-> io.handleError(f)
    }
  }

  testAsync("IO#redeemWith(raiseError, f) <-> IO#flatMap") { implicit ec =>
    check { (io: IO[Int], f: Int => IO[Int]) =>
      io.redeemWith(IO.raiseError, f) <-> io.flatMap(f)
    }
  }

  testAsync("IO#redeemWith(f, pure) <-> IO#handleErrorWith") { implicit ec =>
    check { (io: IO[Int], f: Throwable => IO[Int]) =>
      io.redeemWith(f, IO.pure) <-> io.handleErrorWith(f)
    }
  }

  test("unsafeRunSync works for bracket") {
    var effect = 0
    val io = SyncIO(1).bracket(x => SyncIO(x + 1))(_ => SyncIO(effect += 1))
    io.unsafeRunSync() shouldBe 2
    effect shouldBe 1
  }
}
