/*
 * Copyright 2020-2021 Typelevel
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

package cats.effect.std.syntax

import cats.effect.std.MapRef
import cats.syntax.all._

trait MapRefSyntax {
  implicit def mapRefOptionSyntax[F[_], K, V](mRef: MapRef[F, K, Option[V]]): MapRefOptionOps[F, K, V] =
    new MapRefOptionOps(mRef)
}


final class MapRefOptionOps[F[_], K, V] private[syntax] (private[syntax] val mRef: MapRef[F, K, Option[V]]){
  def unsetKey(k: K): F[Unit] =
    mRef(k).set(None)
  def setKeyValue(k: K, v: V): F[Unit] = 
    mRef(k).set(v.some)
  def getAndSetKeyValue(k: K, v: V): F[Option[V]] = 
    mRef(k).getAndSet(v.some)

  def updateKeyValueIfSet(k: K, f: V => V): F[Unit] = 
    mRef(k).update{
      case None => None
      case Some(v) => f(v).some
    }

  def modifyKeyValueIfSet[B](k: K, f: V => (V, B)): F[Option[B]] =
    mRef(k).modify {
      case None => (None, None)
      case Some(v) => 
        val (set, out) = f(v)
        (set.some, out.some)
    }
}