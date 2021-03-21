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

// defined in Java for the JVM, Scala for ScalaJS (where object field access is faster)
private[effect] object IOFiberConstants {

  val MaxStackDepth: Int = 512

  // continuation ids (should all be inlined)
  val MapK: Byte = 0
  val FlatMapK: Byte = 1
  val CancelationLoopK: Byte = 2
  val RunTerminusK: Byte = 3
  val EvalOnK: Byte = 4
  val HandleErrorWithK: Byte = 5
  val OnCancelK: Byte = 6
  val UncancelableK: Byte = 7
  val UnmaskK: Byte = 8
  val AttemptK: Byte = 9

  // resume ids
  val ExecR: Byte = 0
  val AsyncContinueSuccessfulR: Byte = 1
  val AsyncContinueFailedR: Byte = 2
  val BlockingR: Byte = 3
  val AfterBlockingSuccessfulR: Byte = 4
  val AfterBlockingFailedR: Byte = 5
  val EvalOnR: Byte = 6
  val CedeR: Byte = 7
  val AutoCedeR: Byte = 8
  val DoneR: Byte = 9

  // ContState tags
  val ContStateInitial: Int = 0
  val ContStateWaiting: Int = 1
  val ContStateWinner: Int = 2
  val ContStateResult: Int = 3
}
