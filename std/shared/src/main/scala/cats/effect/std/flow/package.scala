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

package cats.effect.std

/**
 * Implementation of the reactive-streams protocol for cats-effect; based on Java Flow.
 *
 * @example
 *   {{{
 *   import cats.effect.{IO, Resource}
 *   import std.flow.syntax._ scala>
 *   import java.util.concurrent.Flow.Publisher
 *   import cats.effect.unsafe.implicits.global
 *   val upstream: IO[Int] = IO.pure(1)
 *   val publisher: Resource[IO, Publisher[Int]] = upstream.toPublisher
 *   val downstream: IO[Int] = publisher.use(_.toEffect[IO])
 *   downstream.unsafeRunSync()
 *   // res0: Int = 1
 *   }}}
 *
 * @see
 *   [[java.util.concurrent.Flow]]
 */
package object flow {}
