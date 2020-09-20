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

package cats
package effect

import org.specs2.specification.core.Execution
import org.specs2.execute._

import cats.syntax.all._
import scala.concurrent.duration._

class ContSpec extends BaseSpec { outer =>

  def realNoTimeout[A: AsResult](test: => IO[A]): Execution =
    Execution.withEnvAsync(_ => test.unsafeToFuture()(runtime()))

  def execute(io: IO[_], times: Int, i: Int = 0): IO[Success] = {
//    println(i)
    if (i == times) IO(success)
    else io >> execute(io, times, i + 1)
  }

  // TODO move these to IOSpec. Generally review our use of `ticked` in IOSpec
  // various classcast exceptions and/or ByteStack going out of bound
  "get resumes" in real {
    val io = IO.cont[Int].flatMap { case (get, resume) =>
        IO(resume(Right(42))) >> get
      }

    val test = io.flatMap(r => IO(r mustEqual 42))

    execute(test, 100000)
  }

  "callback resumes" in real {
   val (scheduler, close) = unsafe.IORuntime.createDefaultScheduler()

    val io = IO.cont[Int] flatMap { case (get, resume) =>
        IO(scheduler.sleep(10.millis, () => resume(Right(42)))) >> get
    }

    val test = io.flatMap(r => IO(r mustEqual 42))

    execute(test, 100).guarantee(IO(close()))
  }

  "cont.get can be canceled" in real {

    def never = IO.cont[Int].flatMap { case (get, _) => get }
    val io = never.start.flatMap(_.cancel)

    execute(io, 100000)
  }

  // deferred.get cannot be canceled
  // the latch is not stricly needed to observe this, but it does make it fail more reliably
  // you need `complete`, without it the text succeeds
  "focus" in real {
    import kernel._

    def wait(syncLatch: Ref[IO, Boolean]): IO[Unit] =
      syncLatch.get.flatMap { switched =>
        (IO.cede >> wait(syncLatch)).whenA(!switched)
      }

    val io = {
      for {
        d <- Def[Unit]
        latch <- Ref[IO].of(false)
        fb <- (latch.set(true) *> d.get).start
        _ <- wait(latch)
        _ <- d.complete(())
        _ <- fb.cancel
      } yield ()
    }

    execute(io, 100000)
  }
}


  import java.util.concurrent.atomic.AtomicReference

  import scala.annotation.tailrec
  import scala.collection.immutable.LongMap

object Def {
  sealed abstract private class State[A]
  private object State {
    final case class Set[A](a: A) extends State[A]
    final case class Unset[A](readers: LongMap[A => Unit], nextId: Long) extends State[A]

    val initialId = 1L
    val dummyId = 0L
  }

  def apply[A]: IO[Def[A]] = IO(new Def)

  def async[A](k: (Either[Throwable, A] => Unit) => IO[Option[IO[Unit]]]): IO[A] =
    IO.uncancelable { poll =>
      IO.cont[A] flatMap { case (get, cb) =>
        k(cb) flatMap {
          case Some(fin) => poll(get).onCancel(fin)
          case None => poll(get)
        }
      }
    }

}

final class Def[A] {
  import Def._

    // shared mutable state
    private[this] val ref = new AtomicReference[State[A]](
      State.Unset(LongMap.empty, State.initialId)
    )

    def get: IO[A] = {
      // side-effectful
      def addReader(awakeReader: A => Unit): Long = {
        @tailrec
        def loop(): Long =
          ref.get match {
            case State.Set(a) =>
              awakeReader(a)
              State.dummyId // never used
            case s @ State.Unset(readers, nextId) =>
              val updated = State.Unset(
                readers + (nextId -> awakeReader),
                nextId + 1
              )

              if (!ref.compareAndSet(s, updated)) loop()
              else nextId
          }

        loop()
      }

      // side-effectful
      def deleteReader(id: Long): Unit = {
        @tailrec
        def loop(): Unit =
          ref.get match {
            case State.Set(_) => ()
            case s @ State.Unset(readers, _) =>
              val updated = s.copy(readers = readers - id)
              if (!ref.compareAndSet(s, updated)) loop()
              else ()
          }

        loop()
      }

      IO.defer {
        ref.get match {
          case State.Set(a) =>
            IO.pure(a)
          case State.Unset(_, _) =>
            Def.async[A] { cb =>
              val resume = (a: A) => cb(Right(a))
              val id = addReader(awakeReader = resume)
              val onCancel = IO.delay(deleteReader(id))

              onCancel.some.pure[IO]
            }
        }
      }
    }

    def tryGet: IO[Option[A]] =
      IO.delay {
        ref.get match {
          case State.Set(a) => Some(a)
          case State.Unset(_, _) => None
        }
      }

    def complete(a: A): IO[Boolean] = {
      def notifyReaders(readers: LongMap[A => Unit]): IO[Unit] = {
        // LongMap iterators return values in unsigned key order,
        // which corresponds to the arrival order of readers since
        // insertion is governed by a monotonically increasing id
        val cursor = readers.valuesIterator
        var acc = IO.unit

        while (cursor.hasNext) {
          val next = cursor.next()
          val task = IO.delay(next(a))
          acc = acc >> task
        }

        acc
      }

      // side-effectful (even though it returns IO[Unit])
      @tailrec
      def loop(): IO[Boolean] =
        ref.get match {
          case State.Set(_) =>
            IO.pure(false)
          case s @ State.Unset(readers, _) =>
            val updated = State.Set(a)
            if (!ref.compareAndSet(s, updated)) loop()
            else {
              val notify = if (readers.isEmpty) IO.unit else notifyReaders(readers)
              notify.as(true)
            }
        }

      IO.defer(loop())
    }
  }
