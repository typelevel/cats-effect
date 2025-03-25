/*
 * Copyright 2020-2025 Typelevel
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

package cats
package effect
package std

import scala.concurrent.duration.*

class LockSuite extends BaseSuite {

  tests("CustomLock", Lock[IO])

  def tests(name: String, lockIO: IO[Lock[IO]]): Unit = {

    real(s"$name allows multiple shared access") {
      val res = lockIO.flatMap { lock =>
        for {
          _ <- lock.shared.use(_ => IO.sleep(100.millis)).start
          _ <- IO.sleep(10.millis)
          result <- lock.tryShared.use(IO.pure)
        } yield result
      }
      res.mustEqual(true)
    }

    real(s"$name exclusive blocks shared access") {
      val res = lockIO.flatMap { lock =>
        for {
          _ <- lock.exclusive.use(_ => IO.sleep(200.millis)).start
          _ <- IO.sleep(10.millis)
          result <- lock.tryShared.use(IO.pure)
        } yield result
      }
      res.mustEqual(false)
    }

    real(s"$name exclusive blocks exclusive access") {
      val res = lockIO.flatMap { lock =>
        for {
          _ <- lock.exclusive.use(_ => IO.sleep(200.millis)).start
          _ <- IO.sleep(10.millis)
          result <- lock.tryExclusive.use(IO.pure)
        } yield result
      }
      res.mustEqual(false)
    }

    real(s"$name shared blocks exclusive access") {
      val res = lockIO.flatMap { lock =>
        for {
          _ <- lock.shared.use(_ => IO.sleep(100.millis)).start
          _ <- IO.sleep(10.millis)
          result <- lock.tryExclusive.use(IO.pure)
        } yield result
      }
      res.mustEqual(false)
    }

    real(s"$name exclusive prevents new shared access when enqueued") {
      val res = lockIO.flatMap { lock =>
        for {
          _ <- lock.exclusive.use(_ => IO.sleep(200.millis)).start
          _ <- IO.sleep(10.millis)
          _ <- lock.exclusive.use(_ => IO.sleep(100.millis)).start
          _ <- IO.sleep(10.millis)
          result <- lock.tryShared.use(IO.pure)
        } yield result
      }
      res.mustEqual(false)
    }

    real(s"$name reentrant shared access is allowed") {
      val identity = "fiber-1"
      val lockIO = Lock[IO, String](Lock.IdentityProvider.constant(identity))

      val res = lockIO.flatMap { lock =>
        lock.shared.use(_ =>
          lock.tryShared.use(IO.pure)
        )
      }

      res.mustEqual(true)
    }

    real(s"$name reentrant exclusive access is allowed") {
      val identity = "fiber-1"
      val lockIO = Lock[IO, String](Lock.IdentityProvider.constant(identity))

      val res = lockIO.flatMap { lock =>
        lock.exclusive.use(_ =>
          lock.tryExclusive.use(IO.pure)
        )
      }

      res.mustEqual(true)
    }

    real(s"$name releases shared lock properly") {
      val res = lockIO.flatMap { lock =>
        for {
          _ <- lock.shared.use(_ => IO.unit)
          result <- lock.tryExclusive.use(IO.pure)
        } yield result
      }
      res.mustEqual(true)
    }

    real(s"$name releases exclusive lock properly") {
      val res = lockIO.flatMap { lock =>
        for {
          _ <- lock.exclusive.use(_ => IO.unit)
          result <- lock.tryExclusive.use(IO.pure)
        } yield result
      }
      res.mustEqual(true)
    }

    real(s"$name tryShared succeeds when no lock is held") {
      lockIO.flatMap(_.tryShared.use(IO.pure)).mustEqual(true)
    }

    real(s"$name tryExclusive succeeds when no lock is held") {
      lockIO.flatMap(_.tryExclusive.use(IO.pure)).mustEqual(true)
    }

    real(s"$name tryExclusive fails when shared lock is held") {
      val res = lockIO.flatMap { lock =>
        for {
          _ <- lock.shared.use(_ => IO.sleep(100.millis)).start
          _ <- IO.sleep(10.millis)
          result <- lock.tryExclusive.use(IO.pure)
        } yield result
      }
      res.mustEqual(false)
    }

    real(s"$name tryExclusive fails when shared lock is held") {
      val res = lockIO.flatMap { lock =>
        for {
          _ <- lock.shared.use(_ => IO.sleep(100.millis)).start
          _ <- IO.sleep(10.millis)
          result <- lock.tryExclusive.use(IO.pure)
        } yield result
      }
      res.mustEqual(false)
    }
  }
}
