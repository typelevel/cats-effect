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

package cats.effect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

class IOFiberUtils {

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static final MethodType ON_SPIN_WAIT_METHOD_TYPE = MethodType.methodType(void.class);
  private static final MethodHandle ON_SPIN_WAIT_METHOD_HANDLE;

  static {
    ON_SPIN_WAIT_METHOD_HANDLE = makeMethodHandle();
  }

  static void onSpinWait() throws Throwable {
    ON_SPIN_WAIT_METHOD_HANDLE.invokeExact();
  }
  
  private static void fauxOnSpinWait() {
  }
  
  private static MethodHandle makeMethodHandle() {
    try {
      return LOOKUP.findStatic(Thread.class, "onSpinWait", ON_SPIN_WAIT_METHOD_TYPE);
    } catch (NoSuchMethodException e) {
      try {
        return LOOKUP.findStatic(IOFiberUtils.class, "fauxOnSpinWait", ON_SPIN_WAIT_METHOD_TYPE);
      } catch (Throwable t) {
        throw new ExceptionInInitializerError(t);
      }
    } catch (IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
