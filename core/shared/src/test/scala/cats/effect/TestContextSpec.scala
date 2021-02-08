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

// package cats.effect

// import cats.implicits._
// import scala.concurrent.duration._

// class TestContextSpec extends BaseSpec {
//   def io: IO[Unit] =
//     for {
//       fiber <- IO.unit.foreverM.start
//       _ <- IO(println("A"))
//       _ <- IO.sleep(100.millis)
//       _ <- IO(println("B"))
//       _ <- fiber.cancel
//     } yield ()

//   // "tc works" in real {
//   //   io.as(success)
//   // }

//   // "tc deadlocks" in ticked { implicit ticker => io must completeAs(()) }
// }
