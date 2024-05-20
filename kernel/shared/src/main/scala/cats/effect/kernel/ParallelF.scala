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

//See https://failex.blogspot.com/2017/04/the-high-cost-of-anyval-subclasses.html
object Par {
  sealed abstract class ParallelFImpl extends Serializable {
    type T[F[_], A]
    def apply[F[_], A](fa: F[A]): T[F, A]
    def value[F[_], A](t: T[F, A]): F[A]
  }

  type ParallelF[F[_], A] = instance.T[F, A]

  object ParallelF {

    def apply[F[_], A](fa: F[A]): ParallelF[F, A] = instance[F, A](fa)

    def value[F[_], A](t: ParallelF[F, A]): F[A] = instance.value(t)

  }

  val instance: ParallelFImpl = new ParallelFImpl {
    type T[F[_], A] = F[A]

    override def apply[F[_], A](fa: F[A]): T[F, A] = fa

    override def value[F[_], A](t: T[F, A]): F[A] = t

  }

}
