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

package cats.effect;

// defined in Java since Scala doesn't let us define static fields
final class IOFiberConstants {

  static final int MaxStackDepth = 512;

  // continuation ids (should all be inlined)
  static final byte MapK = 0;
  static final byte FlatMapK = 1;
  static final byte CancelationLoopK = 2;
  static final byte RunTerminusK = 3;
  static final byte EvalOnK = 4;
  static final byte HandleErrorWithK = 5;
  static final byte OnCancelK = 6;
  static final byte UncancelableK = 7;
  static final byte UnmaskK = 8;
  static final byte AttemptK = 9;

  // resume ids
  static final byte ExecR = 0;
  static final byte AsyncContinueSuccessfulR = 1;
  static final byte AsyncContinueFailedR = 2;
  static final byte AsyncContinueCanceledR = 3;
  static final byte AsyncContinueCanceledWithFinalizerR = 4;
  static final byte BlockingR = 5;
  static final byte CedeR = 6;
  static final byte AutoCedeR = 7;
  static final byte DoneR = 8;
}
