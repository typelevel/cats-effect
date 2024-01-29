/*
 * Copyright 2020-2024 Typelevel
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

final class SyncIOConstants {

  public static final int MaxStackDepth = 512;

  public static final byte MapK = 0;
  public static final byte FlatMapK = 1;
  public static final byte HandleErrorWithK = 2;
  public static final byte RunTerminusK = 3;
  public static final byte AttemptK = 4;
}
