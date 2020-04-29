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

object Test {
  class Base()
  case class Derived() extends Base

  def fBase[F[+_]](implicit F: Sync[F]): F[Either[Base, Nothing]] = F.delay(Left(new Base()))
  def fDerived[F[+_]](implicit F: Sync[F]): F[Either[Derived, Nothing]] = F.delay(Left(Derived()))

  def f[F[+_]](implicit F: Sync[F]): F[Either[Base, Nothing]] =
    if (true) {
      fBase
    } else {
      fDerived
    }

  def g2[F[+_, +_]](implicit F: Sync[F[Throwable, ?]]): Resource[F[Throwable, ?], Either[Base, Nothing]] =
    Resource
      .liftF[F[Throwable, +?], Either[Base, Nothing]](f[F[Throwable, +?]])
      .map(x => x)

  // this one fails, but not the above
  def g[F[+_]](implicit F: Sync[F]): Resource[F, Either[Base, Nothing]] =
    Resource
      .liftF[F, Either[Base, Nothing]](f[F])
      .map(x => x)
      .flatMap(x => Resource.pure[F, Either[Base, Nothing]](x))

}
