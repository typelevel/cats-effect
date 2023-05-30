/*
 * Copyright 2020-2023 Typelevel
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

/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package cats.effect.std

import cats.Applicative
import cats.effect.kernel.Sync
import cats.effect.std.Random.ScalaRandom

private[std] trait UUIDGenCompanionPlatform extends UUIDGenCompanionPlatformLowPriority

private[std] trait UUIDGenCompanionPlatformLowPriority {

  private implicit def secureRandom[F[_]](implicit ev: Sync[F]): SecureRandom[F] =
    new ScalaRandom[F](Applicative[F].pure(new SecureRandom.JavaSecureRandom()))
      with SecureRandom[F] {}

  implicit def fromSync[F[_]](implicit ev: Sync[F]): UUIDGen[F] =
    UUIDGen.fromSecureRandom[F]

}
