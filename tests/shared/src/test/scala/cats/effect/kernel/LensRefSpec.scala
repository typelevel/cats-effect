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
package effect
package kernel

import cats.Eq
import cats.data.State

import scala.concurrent.duration._

class LensRefSpec extends BaseSpec with DetectPlatform { outer =>

  val smallDelay: IO[Unit] = IO.sleep(20.millis)

  implicit val integerEq: Eq[Integer] = new Eq[Integer] {
    override def eqv(a: Integer, b: Integer) = a == b
  }

  implicit val integerShow: Show[Integer] = new Show[Integer] {
    override def show(a: Integer) = a.toString
  }

  implicit val fooEq: Eq[Foo] = new Eq[Foo] {
    override def eqv(a: Foo, b: Foo) = a == b
  }

  implicit val fooShow: Show[Foo] = new Show[Foo] {
    override def show(a: Foo) = a.toString
  }

  implicit def tuple3Show[A, B, C](
      implicit A: Show[A],
      B: Show[B],
      C: Show[C]): Show[(A, B, C)] =
    new Show[(A, B, C)] {
      override def show(x: (A, B, C)) =
        "(" + A.show(x._1) + "," + B.show(x._2) + "," + C.show(x._3) + ")"
    }

  case class Foo(bar: Integer, baz: Integer)

  object Foo {
    def get(foo: Foo): Integer = foo.bar

    def set(foo: Foo)(bar: Integer): Foo = foo.copy(bar = bar)
  }

  "ref lens" should {

    "get - returns B" in ticked { implicit ticker =>
      val op = for {
        refA <- Ref[IO].of(Foo(0, -1))
        refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get(_), Foo.set(_))
        result <- refB.get
      } yield result

      op must completeAs(0: Integer)
    }

    "set - modifies underlying Ref" in ticked { implicit ticker =>
      val op = for {
        refA <- Ref[IO].of(Foo(0, -1))
        refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
        _ <- refB.set(1)
        result <- refA.get
      } yield result

      op must completeAs(Foo(1, -1))
    }

    "getAndSet - modifies underlying Ref and returns previous value" in ticked {
      implicit ticker =>
        val op = for {
          refA <- Ref[IO].of(Foo(0, -1))
          refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
          oldValue <- refB.getAndSet(1)
          a <- refA.get
        } yield (oldValue, a)

        op must completeAs((0: Integer, Foo(1, -1)))
    }

    "update - modifies underlying Ref" in ticked { implicit ticker =>
      val op = for {
        refA <- Ref[IO].of(Foo(0, -1))
        refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
        _ <- refB.update(_ + 1)
        a <- refA.get
      } yield a

      op must completeAs(Foo(1, -1))
    }

    "modify - modifies underlying Ref and returns a value" in ticked { implicit ticker =>
      val op = for {
        refA <- Ref[IO].of(Foo(0, -1))
        refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
        result <- refB.modify(bar => (bar + 1, 10))
        a <- refA.get
      } yield (result, a)

      op must completeAs((10, Foo(1, -1)))
    }

    "tryUpdate - successfully modifies underlying Ref" in ticked { implicit ticker =>
      val op = for {
        refA <- Ref[IO].of(Foo(0, -1))
        refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
        result <- refB.tryUpdate(_ + 1)
        a <- refA.get
      } yield (result, a)

      op must completeAs((true, Foo(1, -1)))
    }

    if (!isJS && !isNative) // concurrent modification impossible
      "tryUpdate - fails to modify original value if it's already been modified concurrently" in ticked {
        implicit ticker =>
          val updateRefUnsafely: Ref[IO, Integer] => Unit = { (ref: Ref[IO, Integer]) =>
            unsafeRun(ref.set(5))
            ()
          }

          val op = for {
            refA <- Ref[IO].of(Foo(0, -1))
            refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
            result <- refB.tryUpdate { currentValue =>
              updateRefUnsafely(refB)
              currentValue + 1
            }
            a <- refA.get
          } yield (result, a)

          op must completeAs((false, Foo(5, -1)))
      }
    else ()

    "tryModify - successfully modifies underlying Ref" in ticked { implicit ticker =>
      val op = for {
        refA <- Ref[IO].of(Foo(0, -1))
        refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
        result <- refB.tryModify(bar => (bar + 1, "A"))
        a <- refA.get
      } yield (result, a)

      op must completeAs((Some("A"), Foo(1, -1)))
    }

    if (!isJS && !isNative) // concurrent modification impossible
      "tryModify - fails to modify original value if it's already been modified concurrently" in ticked {
        implicit ticker =>
          val updateRefUnsafely: Ref[IO, Integer] => Unit = { (ref: Ref[IO, Integer]) =>
            unsafeRun(ref.set(5))
            ()
          }

          val op = for {
            refA <- Ref[IO].of(Foo(0, -1))
            refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
            result <- refB.tryModify { currentValue =>
              updateRefUnsafely(refB)
              (currentValue + 1, 10)
            }
            a <- refA.get
          } yield (result, a)

          op must completeAs((None, Foo(5, -1)))
      }
    else ()

    "tryModifyState - successfully modifies underlying Ref" in ticked { implicit ticker =>
      val op = for {
        refA <- Ref[IO].of(Foo(0, -1))
        refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
        result <- refB.tryModifyState(State.apply(x => (x + 1, "A")))
        a <- refA.get
      } yield (result, a)

      op must completeAs((Some("A"), Foo(1, -1)))
    }

    "modifyState - successfully modifies underlying Ref" in ticked { implicit ticker =>
      val op = for {
        refA <- Ref[IO].of(Foo(0, -1))
        refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
        result <- refB.modifyState(State.apply(x => (x + 1, "A")))
        a <- refA.get
      } yield (result, a)

      op must completeAs(("A", Foo(1, -1)))
    }

    "access - successfully modifies underlying Ref" in ticked { implicit ticker =>
      val op = for {
        refA <- Ref[IO].of(Foo(0, -1))
        refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
        valueAndSetter <- refB.access
        (value, setter) = valueAndSetter
        success <- setter(value + 1)
        a <- refA.get
      } yield (success, a)

      op must completeAs((true, Foo(1, -1)))
    }

    "access - setter fails to modify underlying Ref if value is modified before setter is called" in ticked {
      implicit ticker =>
        val op = for {
          refA <- Ref[IO].of(Foo(0, -1))
          refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
          valueAndSetter <- refB.access
          (value, setter) = valueAndSetter
          _ <- refA.set(Foo(5, -1))
          success <- setter(value + 1)
          a <- refA.get
        } yield (success, a)

        op must completeAs((false, Foo(5, -1)))
    }

    "access - setter fails the second time" in ticked { implicit ticker =>
      val op = for {
        refA <- Ref[IO].of(Foo(0, -1))
        refB = Ref.lens[IO, Foo, Integer](refA)(Foo.get, Foo.set)
        valueAndSetter <- refB.access
        (_, setter) = valueAndSetter
        result1 <- setter(1)
        result2 <- setter(2)
        a <- refA.get
      } yield (result1, result2, a)

      op must completeAs((true, false, Foo(1, -1)))
    }

  }
}
