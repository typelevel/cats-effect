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

package cats.effect

import cats.data.{Ior, IorT}
import cats.Order
import cats.laws.discipline.arbitrary._
import cats.effect.laws.AsyncTests
import cats.effect.kernel.testkit.{SyncTypeGenerators, TestContext}
import cats.implicits._

import org.scalacheck.Prop

import org.specs2.ScalaCheck

import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

class IorTIOSpec extends IOPlatformSpecification with Discipline with ScalaCheck with BaseSpec {
  outer =>

  import SyncTypeGenerators._

  // we just need this because of the laws testing, since the prop runs can interfere with each other
  sequential

  implicit def ordIorTIOFD(implicit ticker: Ticker): Order[IorT[IO, Int, FiniteDuration]] =
    Order by { ioaO => unsafeRun(ioaO.value).fold(None, _ => None, fa => fa) }

  //TODO remove once https://github.com/typelevel/cats/pull/3555 is released
  implicit def orderIor[A, B](
      implicit A: Order[A],
      B: Order[B],
      AB: Order[(A, B)]): Order[Ior[A, B]] =
    new Order[Ior[A, B]] {

      override def compare(x: Ior[A, B], y: Ior[A, B]): Int =
        (x, y) match {
          case (Ior.Left(a1), Ior.Left(a2)) => A.compare(a1, a2)
          case (Ior.Left(_), _) => -1
          case (Ior.Both(a1, b1), Ior.Both(a2, b2)) => AB.compare((a1, b1), (a2, b2))
          case (Ior.Both(_, _), Ior.Left(_)) => 1
          case (Ior.Both(_, _), Ior.Right(_)) => -1
          case (Ior.Right(b1), Ior.Right(b2)) => B.compare(b1, b2)
          case (Ior.Right(_), _) => 1
        }

    }

  //TODO remove once https://github.com/typelevel/cats/pull/3555 is released
  implicit def orderIorT[F[_], A, B](implicit Ord: Order[F[Ior[A, B]]]): Order[IorT[F, A, B]] =
    Order.by(_.value)

  implicit def execIorT(sbool: IorT[IO, Int, Boolean])(implicit ticker: Ticker): Prop =
    Prop(
      unsafeRun(sbool.value).fold(
        false,
        _ => false,
        iO => iO.fold(false)(i => i.fold(_ => false, _ => true, (_, _) => false)))
    )

  {
    implicit val ticker = Ticker(TestContext())

    checkAll(
      "IorT[IO]",
      AsyncTests[IorT[IO, Int, *]].async[Int, Int, Int](10.millis)
    )
  }

}
