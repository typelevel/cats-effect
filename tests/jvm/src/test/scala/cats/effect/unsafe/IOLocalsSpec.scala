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

package cats.effect
package unsafe

class IOLocalsSpec extends BaseSpec {

  "IOLocals" should {
    "return a default value" in real {
      IOLocal(42).flatMap(local => IO(IOLocals.get(local))).map(_ must beEqualTo(42))
    }

    "return a set value" in real {
      IOLocal(42)
        .flatMap(local => local.set(24) *> IO(IOLocals.get(local)))
        .map(_ must beEqualTo(24))
    }

    "unsafely set" in real {
      IOLocal(42).flatMap(local =>
        IO(IOLocals.set(local, 24)) *> local.get.map(_ must beEqualTo(24)))
    }

    "unsafely reset" in real {
      IOLocal(42)
        .flatMap(local => local.set(24) *> IO(IOLocals.reset(local)) *> local.get)
        .map(_ must beEqualTo(42))
    }

    "unsafely update" in real {
      IOLocal(42)
        .flatMap(local => IO(IOLocals.update(local)(_ * 2)) *> local.get)
        .map(_ must beEqualTo(84))
    }

    "unsafely modify" in real {
      IOLocal(42)
        .flatMap { local =>
          IO {
            IOLocals.modify(local)(x => (x * 2, x.toString)) must beEqualTo("42")
          } *> local.get
        }
        .map(_ must beEqualTo(84))
    }

    "unsafely getAndSet" in real {
      IOLocal(42)
        .flatMap { local =>
          IO {
            IOLocals.getAndSet(local, 24) must beEqualTo(42)
          } *> local.get
        }
        .map(_ must beEqualTo(24))
    }

    "unsafely getAndReset" in real {
      IOLocal(42)
        .flatMap { local =>
          local.set(24) *> IO {
            IOLocals.getAndReset(local) must beEqualTo(24)
          } *> local.get
        }
        .map(_ must beEqualTo(42))
    }
  }

}
