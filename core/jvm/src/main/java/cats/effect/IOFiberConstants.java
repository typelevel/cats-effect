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

  public static int MaxStackDepth = 512;

  // continuation ids (should all be inlined)
  public static byte MapK = 0;
  public static byte FlatMapK = 1;
  public static byte CancelationLoopK = 2;
  public static byte RunTerminusK = 3;
  public static byte AsyncK = 4;
  public static byte EvalOnK = 5;
  public static byte HandleErrorWithK = 6;
  public static byte OnCancelK = 7;
  public static byte UncancelableK = 8;
  public static byte UnmaskK = 9;

  // resume ids
  public static byte ExecR = 0;
  public static byte AsyncContinueR = 1;
  public static byte BlockingR = 2;
  public static byte AfterBlockingSuccessfulR = 3;
  public static byte AfterBlockingFailedR = 4;
  public static byte EvalOnR = 5;
  public static byte CedeR = 6;
}
