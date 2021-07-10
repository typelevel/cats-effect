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

package cats.effect.instances

import cats.{Applicative, Eval, Monad, Parallel, ~>}
import cats.arrow.FunctionK
import cats.effect.IO
import cats.effect.kernel.Par.ParallelF
import cats.effect.unsafe.implicits.global

trait ParEval {
  implicit final val parEvalInstance: Parallel[Eval] =
    new Parallel[Eval] {
      override final type F[x] = IO.Par[x]

      override final val applicative: Applicative[F] =
        all.commutativeApplicativeForParallelF[IO, Throwable]

      override final val monad: Monad[Eval] =
        Eval.catsBimonadForEval

      override final val sequential: IO.Par ~> Eval =
        new FunctionK[IO.Par, Eval] {
          override final def apply[A](ioPar: IO.Par[A]): Eval[A] =
            Eval.later(ParallelF.value(ioPar).unsafeRunSync())
        }

      override final val parallel: Eval ~> IO.Par =
        new FunctionK[Eval, IO.Par] {
          override final def apply[A](eval: Eval[A]): IO.Par[A] =
            ParallelF(IO(eval.value))
        }
    }
}
