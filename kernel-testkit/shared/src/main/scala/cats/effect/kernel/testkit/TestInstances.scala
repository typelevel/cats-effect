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

package cats.effect.kernel
package testkit

import cats.Applicative
import cats.kernel.Eq
import cats.syntax.all._

import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary.arbitrary

trait TestInstances {

  implicit def arbitraryResource[F[_], A](
      implicit F: Applicative[F],
      AFA: Arbitrary[F[A]],
      AFU: Arbitrary[F[Unit]],
      AA: Arbitrary[A]
  ): Arbitrary[Resource[F, A]] =
    Arbitrary(Gen.delay(genResource[F, A]))

  // Consider improving this a strategy similar to Generators.
  // Doesn't include interruptible resources
  def genResource[F[_], A](
      implicit F: Applicative[F],
      AFA: Arbitrary[F[A]],
      AFU: Arbitrary[F[Unit]],
      AA: Arbitrary[A]
  ): Gen[Resource[F, A]] = {
    def genAllocate: Gen[Resource[F, A]] =
      for {
        alloc <- arbitrary[F[A]]
        dispose <- arbitrary[F[Unit]]
      } yield Resource(alloc.map(a => a -> dispose))

    def genBind: Gen[Resource[F, A]] =
      genAllocate.map(_.flatMap(a => Resource.pure[F, A](a)))

    def genEval: Gen[Resource[F, A]] =
      arbitrary[F[A]].map(Resource.eval)

    def genPure: Gen[Resource[F, A]] =
      arbitrary[A].map(Resource.pure)

    Gen.frequency(
      5 -> genAllocate,
      1 -> genBind,
      1 -> genEval,
      1 -> genPure
    )
  }

  /**
   * Defines equality for a `Resource`. Two resources are deemed equivalent if they allocate an
   * equivalent resource. Cleanup, which is run purely for effect, is not considered.
   */
  implicit def eqResource[F[_], A](
      implicit E: Eq[F[A]],
      F: MonadCancel[F, Throwable]): Eq[Resource[F, A]] =
    new Eq[Resource[F, A]] {
      def eqv(x: Resource[F, A], y: Resource[F, A]): Boolean =
        E.eqv(x.use(F.pure), y.use(F.pure))
    }
}
