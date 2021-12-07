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
private object IOFiberConstants {

  final val MaxStackDepth: Int = 512

  // continuation ids (should all be inlined)
  final val MapK: Byte = 0
  final val FlatMapK: Byte = 1
  final val CancelationLoopK: Byte = 2
  final val RunTerminusK: Byte = 3
  final val EvalOnK: Byte = 4
  final val HandleErrorWithK: Byte = 5
  final val OnCancelK: Byte = 6
  final val UncancelableK: Byte = 7
  final val UnmaskK: Byte = 8
  final val AttemptK: Byte = 9
  final val UnyieldingK: Byte = 10;

  // resume ids
  final val ExecR: Byte = 0
  final val AsyncContinueSuccessfulR: Byte = 1
  final val AsyncContinueFailedR: Byte = 2
  final val AsyncContinueCanceledR: Byte = 3
  final val AsyncContinueCanceledWithFinalizerR = 4
  final val BlockingR: Byte = 5
  final val CedeR: Byte = 6
  final val AutoCedeR: Byte = 7
  final val DoneR: Byte = 8

  // ContState tags
  final val ContStateInitial: Int = 0
  final val ContStateWaiting: Int = 1
  final val ContStateWinner: Int = 2
  final val ContStateResult: Int = 3
}
