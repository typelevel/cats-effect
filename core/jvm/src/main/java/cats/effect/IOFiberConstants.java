/*
 * Copyright 2020 Typelevel
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

  // TODO there will likely be more in here, such as ARM ≥8.1
  public static final boolean AggressiveCasSemantics = System.getProperty("os.arch").startsWith("x86");

  public static final int MaxStackDepth = 512;

  // continuation ids (should all be inlined)
  public static final byte MapK = 0;
  public static final byte FlatMapK = 1;
  public static final byte CancelationLoopK = 2;
  public static final byte RunTerminusK = 3;
  public static final byte EvalOnK = 4;
  public static final byte HandleErrorWithK = 5;
  public static final byte OnCancelK = 6;
  public static final byte UncancelableK = 7;
  public static final byte UnmaskK = 8;
  public static final byte AttemptK = 9;
  public static final byte AutoCedeK = 10;

  // resume ids
  public static final byte ExecR = 0;
  public static final byte AsyncContinueR = 1;
  public static final byte BlockingR = 2;
  public static final byte AfterBlockingSuccessfulR = 3;
  public static final byte AfterBlockingFailedR = 4;
  public static final byte EvalOnR = 5;
  public static final byte CedeR = 6;
  public static final byte DoneR = 7;
}
