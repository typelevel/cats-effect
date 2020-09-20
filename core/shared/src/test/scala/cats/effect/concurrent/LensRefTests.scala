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

package cats.effect
package concurrent

import cats.data.State
import cats.effect.IO

class LensRefTests extends CatsEffectSuite {

  case class Foo(bar: Integer, baz: Integer)

  object Foo {
    def get(foo: Foo): Integer = foo.bar

    def set(foo: Foo)(bar: Integer): Foo = foo.copy(bar = bar)
  }

  test("get - returns B") {
    for {
      refA <- Ref[IO].of(Foo(0, -1))
      refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
      v <- refB.get
    } yield assertEquals(v.toInt, 0)
  }

  test("set - modifies underlying Ref") {
    for {
      refA <- Ref[IO].of(Foo(0, -1))
      refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
      _ <- refB.set(1)
      v <- refA.get
    } yield assertEquals(v, Foo(1, -1))
  }

  test("getAndSet - modifies underlying Ref and returns previous value") {
    for {
      refA <- Ref[IO].of(Foo(0, -1))
      refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
      oldValue <- refB.getAndSet(1)
      a <- refA.get
    } yield assertEquals((oldValue.toInt, a), (0, Foo(1, -1)))
  }

  test("update - modifies underlying Ref") {
    for {
      refA <- Ref[IO].of(Foo(0, -1))
      refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
      _ <- refB.update(_ + 1)
      a <- refA.get
    } yield assertEquals(a, Foo(1, -1))
  }

  test("modify - modifies underlying Ref and returns a value") {
    for {
      refA <- Ref[IO].of(Foo(0, -1))
      refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
      result <- refB.modify(bar => (bar + 1, 10))
      a <- refA.get
    } yield assertEquals((result, a), (10, Foo(1, -1)))
  }

  test("tryUpdate - successfully modifies underlying Ref") {
    for {
      refA <- Ref[IO].of(Foo(0, -1))
      refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
      v <- refB.tryUpdate(_ + 1)
      a <- refA.get
    } yield assertEquals((v, a), (true, Foo(1, -1)))
  }

  test("tryUpdate - fails to modify original value if it's already been modified concurrently") {
    val updateRefUnsafely: Ref[IO, Integer] => Unit = _.set(5).unsafeRunSync()

    for {
      refA <- Ref[IO].of(Foo(0, -1))
      refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
      v <- refB.tryUpdate { currentValue =>
        updateRefUnsafely(refB)
        currentValue + 1
      }
      a <- refA.get
    } yield assertEquals((v, a), (false, Foo(5, -1)))
  }

  test("tryModify - successfully modifies underlying Ref") {
    for {
      refA <- Ref[IO].of(Foo(0, -1))
      refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
      v <- refB.tryModify(bar => (bar + 1, "A"))
      a <- refA.get
    } yield assertEquals((v, a), (Some("A"), Foo(1, -1)))
  }

  test("tryModify - fails to modify original value if it's already been modified concurrently") {
    val updateRefUnsafely: Ref[IO, Integer] => Unit = _.set(5).unsafeRunSync()

    for {
      refA <- Ref[IO].of(Foo(0, -1))
      refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
      v <- refB.tryModify { currentValue =>
        updateRefUnsafely(refB)
        (currentValue + 1, 10)
      }
      a <- refA.get
    } yield assertEquals((v, a), (None, Foo(5, -1)))
  }

  test("tryModifyState - successfully modifies underlying Ref") {
    for {
      refA <- Ref[IO].of(Foo(0, -1))
      refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
      v <- refB.tryModifyState(State.apply(x => (x + 1, "A")))
      a <- refA.get
    } yield assertEquals((v, a), (Some("A"), Foo(1, -1)))
  }

  test("modifyState - successfully modifies underlying Ref") {
    for {
      refA <- Ref[IO].of(Foo(0, -1))
      refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
      v <- refB.modifyState(State.apply(x => (x + 1, "A")))
      a <- refA.get
    } yield assertEquals((v, a), ("A", Foo(1, -1)))
  }

  test("access - successfully modifies underlying Ref") {
    for {
      refA <- Ref[IO].of(Foo(0, -1))
      refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
      valueAndSetter <- refB.access
      (value, setter) = valueAndSetter
      success <- setter(value + 1)
      a <- refA.get
    } yield assertEquals((success, a), (true, Foo(1, -1)))
  }

  test("access - successfully modifies underlying Ref after A is modified without affecting B") {
    for {
      refA <- Ref[IO].of(Foo(0, -1))
      refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
      valueAndSetter <- refB.access
      (value, setter) = valueAndSetter
      _ <- refA.update(_.copy(baz = -2))
      success <- setter(value + 1)
      a <- refA.get
    } yield assertEquals((success, a), (true, Foo(1, -2)))
  }

  test("access - setter fails to modify underlying Ref if value is modified before setter is called") {
    for {
      refA <- Ref[IO].of(Foo(0, -1))
      refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
      valueAndSetter <- refB.access
      (value, setter) = valueAndSetter
      _ <- refA.set(Foo(5, -1))
      success <- setter(value + 1)
      a <- refA.get
    } yield assertEquals((success, a), (false, Foo(5, -1)))
  }

  test("access - setter fails the second time") {
    for {
      refA <- Ref[IO].of(Foo(0, -1))
      refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
      valueAndSetter <- refB.access
      (_, setter) = valueAndSetter
      v1 <- setter(1)
      v2 <- setter(2)
      a <- refA.get
    } yield assertEquals((v1, v2, a), (true, false, Foo(1, -1)))
  }
}
