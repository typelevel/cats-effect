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

import cats.data._
import cats.effect.laws.util.TestContext
import cats.implicits._
import scala.util.Success

class ContextShiftTests extends BaseTestsSuite {
  type EitherTIO[A] = EitherT[IO, Throwable, A]
  type OptionTIO[A] = OptionT[IO, A]
  type WriterTIO[A] = WriterT[IO, Int, A]
  type KleisliIO[A] = Kleisli[IO, Int, A]
  type StateTIO[A] = StateT[IO, Int, A]
  type IorTIO[A] = IorT[IO, Int, A]

  testAsync("ContextShift[IO].shift") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val f = cs.shift.unsafeToFuture()
    f.value shouldBe None

    ec.tick()
    f.value shouldBe Some(Success(()))
  }

  testAsync("ContextShift[IO].evalOn") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val ec2 = TestContext()

    val f = cs.evalOn(ec2)(IO(1)).unsafeToFuture()
    f.value shouldBe None

    ec.tick()
    f.value shouldBe None

    ec2.tick()
    f.value shouldBe None
    ec.tick()
    f.value shouldBe Some(Success(1))
  }

  testAsync("ContextShift.evalOnK[IO]") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val ec2 = TestContext()

    val funK = ContextShift.evalOnK[IO](ec2)
    val f = funK(IO(1)).unsafeToFuture()
    f.value shouldBe None

    ec.tick()
    f.value shouldBe None

    ec2.tick()
    f.value shouldBe None
    ec.tick()
    f.value shouldBe Some(Success(1))
  }

  // -- EitherT

  testAsync("Timer[EitherT].shift") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val f = implicitly[ContextShift[EitherTIO]].shift.value.unsafeToFuture()

    f.value shouldBe None

    ec.tick()
    f.value shouldBe Some(Success(Right(())))
  }

  testAsync("Timer[EitherT].evalOn") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val cs2 = implicitly[ContextShift[EitherTIO]]
    val ec2 = TestContext()

    val f = cs2.evalOn(ec2)(EitherT.liftF(IO(1))).value.unsafeToFuture()
    f.value shouldBe None

    ec.tick()
    f.value shouldBe None

    ec2.tick()
    f.value shouldBe None
    ec.tick()
    f.value shouldBe Some(Success(Right(1)))
  }

  // -- OptionT

  testAsync("Timer[OptionT].shift") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val f = implicitly[ContextShift[OptionTIO]].shift.value.unsafeToFuture()

    f.value shouldBe None

    ec.tick()
    f.value shouldBe Some(Success(Some(())))
  }

  testAsync("Timer[OptionT].evalOn") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val cs2 = implicitly[ContextShift[OptionTIO]]
    val ec2 = TestContext()

    val f = cs2.evalOn(ec2)(OptionT.liftF(IO(1))).value.unsafeToFuture()
    f.value shouldBe None

    ec.tick()
    f.value shouldBe None

    ec2.tick()
    f.value shouldBe None
    ec.tick()
    f.value shouldBe Some(Success(Some(1)))
  }

  // -- WriterT

  testAsync("Timer[WriterT].shift") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val f = implicitly[ContextShift[WriterTIO]].shift.value.unsafeToFuture()

    f.value shouldBe None

    ec.tick()
    f.value shouldBe Some(Success(()))
  }

  testAsync("Timer[WriterT].evalOn") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val cs2 = implicitly[ContextShift[WriterTIO]]
    val ec2 = TestContext()

    val f = cs2.evalOn(ec2)(WriterT.liftF[IO, Int, Int](IO(1))).value.unsafeToFuture()
    f.value shouldBe None

    ec.tick()
    f.value shouldBe None

    ec2.tick()
    f.value shouldBe None
    ec.tick()
    f.value shouldBe Some(Success(1))
  }

  // -- StateT

  testAsync("Timer[StateT].shift") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val f = implicitly[ContextShift[StateTIO]].shift.run(0).unsafeToFuture()

    f.value shouldBe None

    ec.tick()
    f.value shouldBe Some(Success((0, ())))
  }

  testAsync("Timer[StateT].evalOn") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val cs2 = implicitly[ContextShift[StateTIO]]
    val ec2 = TestContext()

    val f = cs2.evalOn(ec2)(StateT.liftF[IO, Int, Int](IO(1))).run(0).unsafeToFuture()
    f.value shouldBe None

    ec.tick()
    f.value shouldBe None

    ec2.tick()
    f.value shouldBe None
    ec.tick()
    f.value shouldBe Some(Success((0, 1)))
  }

  // -- Kleisli

  testAsync("Timer[Kleisli].shift") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val f = implicitly[ContextShift[KleisliIO]].shift.run(0).unsafeToFuture()

    f.value shouldBe None

    ec.tick()
    f.value shouldBe Some(Success(()))
  }

  testAsync("Timer[Kleisli].evalOn") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val cs2 = implicitly[ContextShift[KleisliIO]]
    val ec2 = TestContext()

    val f = cs2.evalOn(ec2)(Kleisli.liftF[IO, Int, Int](IO(1))).run(0).unsafeToFuture()
    f.value shouldBe None

    ec.tick()
    f.value shouldBe None

    ec2.tick()
    f.value shouldBe None
    ec.tick()
    f.value shouldBe Some(Success(1))
  }

  // -- IorT

  testAsync("Timer[IorT].shift") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val f = implicitly[ContextShift[IorTIO]].shift.value.unsafeToFuture()

    f.value shouldBe None

    ec.tick()
    f.value shouldBe Some(Success(Ior.Right(())))
  }

  testAsync("Timer[IorT].evalOn") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val cs2 = implicitly[ContextShift[IorTIO]]
    val ec2 = TestContext()

    val f = cs2.evalOn(ec2)(IorT.liftF(IO(1))).value.unsafeToFuture()
    f.value shouldBe None

    ec.tick()
    f.value shouldBe None

    ec2.tick()
    f.value shouldBe None
    ec.tick()
    f.value shouldBe Some(Success(Ior.Right(1)))
  }
}
