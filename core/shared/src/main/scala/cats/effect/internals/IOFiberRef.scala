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

package cats.effect.internals

import java.util.concurrent.atomic.AtomicLong

import cats.effect.IO

private[effect] object IOFiberRef {

  def allocate: IO[FiberRefId] =
    IO.delay {
      nextFiberRefId.getAndIncrement()
    }

  def get[A](refId: FiberRefId): IO[Option[A]] =
    IO.FiberLocal { state =>
      state.get(refId).map(_.asInstanceOf[A])
    }

  def set[A](refId: FiberRefId, value: A): IO[Unit] =
    IO.FiberLocal { state =>
      state.put(refId, value.asInstanceOf[AnyRef])
      ()
    }


  private val nextFiberRefId = new AtomicLong(1)

}
