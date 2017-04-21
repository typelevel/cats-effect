/*
 * Copyright 2017 Typelevel
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

package object implicits extends Effect.ToEffectOps {

  // this has to be implicitly enriched to get around variance questions
  final implicit class IOSyntax[A](val self: IO[A]) extends AnyVal {
    final def liftIO[F[_]: LiftIO]: F[A] = LiftIO[F].liftIO(self)
  }
}
