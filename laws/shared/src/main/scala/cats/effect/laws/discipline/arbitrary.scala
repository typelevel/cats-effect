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
package laws
package discipline

import cats.effect.IO.Par
import cats.effect.internals.IORunLoop
import org.scalacheck.Arbitrary.{arbitrary => getArbitrary}
import org.scalacheck._
import scala.util.Either

object arbitrary {
  implicit def catsEffectLawsArbitraryForIO[A: Arbitrary: Cogen]: Arbitrary[IO[A]] =
    Arbitrary(Gen.delay(genIO[A]))

  implicit def catsEffectLawsArbitraryForSyncIO[A: Arbitrary: Cogen]: Arbitrary[SyncIO[A]] =
    Arbitrary(Gen.delay(genSyncIO[A]))

  implicit def catsEffectLawsArbitraryForIOParallel[A: Arbitrary: Cogen]: Arbitrary[IO.Par[A]] =
    Arbitrary(catsEffectLawsArbitraryForIO[A].arbitrary.map(Par.apply))

  def genIO[A: Arbitrary: Cogen]: Gen[IO[A]] =
    Gen.frequency(
      1 -> genPure[A].map(_.toIO),
      1 -> genApply[A].map(_.toIO),
      1 -> genFail[A].map(_.toIO),
      1 -> genAsync[A],
      1 -> genAsyncF[A],
      1 -> genNestedAsync[A],
      1 -> genCancelable[A],
      1 -> getMapOne[A],
      1 -> getMapTwo[A],
      2 -> genFlatMap[A]
    )

  def genSyncIO[A: Arbitrary: Cogen]: Gen[SyncIO[A]] =
    Gen.frequency(5 -> genPure[A], 5 -> genApply[A], 1 -> genFail[A], 5 -> genBindSuspend[A])

  def genPure[A: Arbitrary]: Gen[SyncIO[A]] =
    getArbitrary[A].map(SyncIO.pure)

  def genApply[A: Arbitrary]: Gen[SyncIO[A]] =
    getArbitrary[A].map(SyncIO.apply(_))

  def genFail[A]: Gen[SyncIO[A]] =
    getArbitrary[Throwable].map(SyncIO.raiseError)

  def genAsync[A: Arbitrary]: Gen[IO[A]] =
    getArbitrary[(Either[Throwable, A] => Unit) => Unit].map(IO.async)

  def genAsyncF[A: Arbitrary]: Gen[IO[A]] =
    getArbitrary[(Either[Throwable, A] => Unit) => Unit].map { k =>
      IO.asyncF(cb => IO(k(cb)))
    }

  def genCancelable[A: Arbitrary: Cogen]: Gen[IO[A]] =
    getArbitrary[IO[A]].map(io => IO.cancelBoundary *> io <* IO.cancelBoundary)

  def genNestedAsync[A: Arbitrary: Cogen]: Gen[IO[A]] =
    getArbitrary[(Either[Throwable, IO[A]] => Unit) => Unit]
      .map(k => IO.async(k).flatMap(x => x))

  def genBindSuspend[A: Arbitrary: Cogen]: Gen[SyncIO[A]] =
    getArbitrary[A].map(SyncIO.apply(_).flatMap(SyncIO.pure))

  def genFlatMap[A: Arbitrary: Cogen]: Gen[IO[A]] =
    for {
      ioa <- getArbitrary[IO[A]]
      f <- getArbitrary[A => IO[A]]
    } yield ioa.flatMap(f)

  def getMapOne[A: Arbitrary: Cogen]: Gen[IO[A]] =
    for {
      ioa <- getArbitrary[IO[A]]
      f <- getArbitrary[A => A]
    } yield ioa.map(f)

  def getMapTwo[A: Arbitrary: Cogen]: Gen[IO[A]] =
    for {
      ioa <- getArbitrary[IO[A]]
      f1 <- getArbitrary[A => A]
      f2 <- getArbitrary[A => A]
    } yield ioa.map(f1).map(f2)

  implicit def catsEffectLawsCogenForIO[A](implicit cga: Cogen[A]): Cogen[IO[A]] =
    Cogen { (seed, io) =>
      IORunLoop.step(io) match {
        case IO.Pure(a) => cga.perturb(seed, a)
        case _          => seed
      }
    }

  implicit def catsEffectLawsCogenForExitCase[E](implicit cge: Cogen[E]): Cogen[ExitCase[E]] =
    Cogen { (seed, e) =>
      e match {
        case ExitCase.Completed  => seed
        case ExitCase.Error(err) => cge.perturb(seed, err)
        case ExitCase.Canceled   => seed.next
      }
    }

  implicit def catsEffectLawsArbitraryForResource[F[_], A](implicit F: Applicative[F],
                                                           AFA: Arbitrary[F[A]],
                                                           AFU: Arbitrary[F[Unit]]): Arbitrary[Resource[F, A]] =
    Arbitrary(Gen.delay(genResource[F, A]))

  implicit def catsEffectLawsArbitraryForResourceParallel[F[_], A](
    implicit A: Arbitrary[Resource[F, A]]
  ): Arbitrary[Resource.Par[F, A]] =
    Arbitrary(A.arbitrary.map(Resource.Par.apply))

  def genResource[F[_], A](implicit F: Applicative[F],
                           AFA: Arbitrary[F[A]],
                           AFU: Arbitrary[F[Unit]]): Gen[Resource[F, A]] = {
    def genAllocate: Gen[Resource[F, A]] =
      for {
        alloc <- getArbitrary[F[A]]
        dispose <- getArbitrary[F[Unit]]
      } yield Resource(F.map(alloc)(a => a -> dispose))

    def genBind: Gen[Resource[F, A]] =
      genAllocate.map(_.flatMap(a => Resource.pure[F, A](a)))

    def genSuspend: Gen[Resource[F, A]] =
      genAllocate.map(r => Resource.suspend(F.pure(r)))

    Gen.frequency(
      5 -> genAllocate,
      1 -> genBind,
      1 -> genSuspend
    )
  }
}
