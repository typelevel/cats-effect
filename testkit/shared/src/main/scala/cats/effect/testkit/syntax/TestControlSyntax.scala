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

package cats.effect
package testkit
package syntax

import cats.effect.unsafe.IORuntimeConfig

trait TestControlSyntax {
  implicit def testControlOps[A](wrapped: IO[A]): TestControlOps[A] =
    new TestControlOps(wrapped)
}

final class TestControlOps[A] private[syntax] (private val wrapped: IO[A]) extends AnyVal {

  def execute[B](config: IORuntimeConfig = IORuntimeConfig(), seed: Option[String] = None)(
      body: (TestControl, () => Option[Either[Throwable, A]]) => B): B =
    TestControl.execute(wrapped, config = config, seed = seed)(body)

  def executeFully(
      config: IORuntimeConfig = IORuntimeConfig(),
      seed: Option[String] = None): Option[Either[Throwable, A]] =
    TestControl.executeFully(wrapped, config = config, seed = seed)
}
