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

package cats.effect

import scala.annotation.{switch, tailrec}
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

private[effect] final class IOFiber[A](name: String) extends Fiber[IO, Throwable, A] {
  import IO._

  private[this] var heapCur: IO[Any] = _
  private[this] var canceled: Boolean = false

  // (Outcome[IO, Throwable, A] => Unit) | SafeArrayStack[Outcome[IO, Throwable, A] => Unit]
  private[this] val callback: AtomicReference[AnyRef] = new AtomicReference()

  private[this] val outcome: AtomicReference[Outcome[IO, Throwable, A]] =
    new AtomicReference()

  def this(heapCur0: IO[A], cb: Outcome[IO, Throwable, A] => Unit) = {
    this("main")
    heapCur = heapCur0
    callback.set(cb)
  }

  def this(heapCur0: IO[A], name: String) = {
    this(name)
    heapCur = heapCur0
  }

  val cancel: IO[Unit] = IO { canceled = true }

  // this needs to push handlers onto the *tail* of the continuation stack...
  val join: IO[Outcome[IO, Throwable, A]] =
    IO async { cb =>
      IO {
        if (outcome.get() == null) {
          val toPush = { (oc: Outcome[IO, Throwable, A]) => cb(Right(oc)) }

          @tailrec
          def loop(): Unit = {
            if (callback.get() == null) {
              if (!callback.compareAndSet(null, toPush)) {
                loop()
              }
            } else {
              val old0 = callback.get()
              if (old0.isInstanceOf[Function1[_, _]]) {
                val old = old0.asInstanceOf[Outcome[IO, Throwable, A] => Unit]

                val stack = new SafeArrayStack[Outcome[IO, Throwable, A] => Unit](4)
                stack.push(old)
                stack.push(toPush)

                if (!callback.compareAndSet(old, stack)) {
                  loop()
                }
              } else {
                val stack = old0.asInstanceOf[SafeArrayStack[Outcome[IO, Throwable, A] => Unit]]
                stack.push(toPush)
              }
            }
          }

          loop()

          // double-check
          if (outcome.get() != null) {
            cb(Right(outcome.get()))    // the implementation of async saves us from double-calls
          }
        } else {
          cb(Right(outcome.get()))
        }

        None
      }
    }

  private[effect] def run(ec: ExecutionContext): Unit = {
    val cur = heapCur
    heapCur = null

    val ctxs = new ArrayStack[ExecutionContext](2)
    ctxs.push(ec)

    val conts = new ArrayStack[(Boolean, Any) => Unit](16)    // TODO tune!

    conts push { (b, ar) =>
      if (b)
        outcome.compareAndSet(null, Outcome.Completed(IO.pure(ar.asInstanceOf[A])))
      else
        outcome.compareAndSet(null, Outcome.Errored(ar.asInstanceOf[Throwable]))

      val cb0 = callback.get()
      if (cb0.isInstanceOf[Function1[_, _]]) {
        val cb = cb0.asInstanceOf[Outcome[IO, Throwable, A] => Unit]
        cb(outcome.get())
      } else if (cb0.isInstanceOf[SafeArrayStack[_]]) {
        val stack = cb0.asInstanceOf[SafeArrayStack[Outcome[IO, Throwable, A] => Unit]]

        val bound = stack.unsafeIndex()
        val buffer = stack.unsafeBuffer()

        val oc = outcome.get()
        var i = 0
        while (i < bound) {
          buffer(i)(oc)
          i += 1
        }
      }

      callback.set(null)    // avoid leaks
    }

    runLoop(cur, ctxs, conts)
  }

  @tailrec
  private[this] def runLoop(
      cur00: IO[Any],
      ctxs: ArrayStack[ExecutionContext],
      conts: ArrayStack[(Boolean, Any) => Unit])
      : Unit = {

    val cur0 = if (cur00 == null) {
      val back = heapCur
      heapCur = null
      back
    } else {
      cur00
    }

    // cur0 will be null when we're semantically blocked
    if (!conts.isEmpty() && cur0 != null) {
      (cur0.tag: @switch) match {
        case 0 =>
          val cur = cur0.asInstanceOf[Pure[Any]]

          conts.pop()(true, cur.value)
          runLoop(null, ctxs, conts)

        case 1 =>
          val cur = cur0.asInstanceOf[Delay[Any]]

          val cb = conts.pop()
          try {
            cb(true, cur.thunk())
          } catch {
            case NonFatal(t) =>
              cb(false, t)
          }

          runLoop(null, ctxs, conts)

        case 2 =>
          val cur = cur0.asInstanceOf[Error]

          conts.pop()(false, cur.t)
          runLoop(null, ctxs, conts)

        case 3 =>
          val cur = cur0.asInstanceOf[Async[Any]]

          val done = new AtomicBoolean()

          /*
           * Three states here:
           *
           * - null (no one has completed, or everyone has)
           * - Left(null) (registration has completed but not callback)
           * - anything else (callback has completed but not registration)
           *
           * The purpose of this buffer is to ensure that registration takes
           * primacy over callbacks in the event that registration produces
           * errors in sequence. This implements a "queueing" semantic to
           * the callbacks, implying that the callback is sequenced after
           * registration has fully completed, giving us deterministic
           * serialization.
           */
          val state = new AtomicReference[Either[Throwable, Any]]()

          def continue(e: Either[Throwable, Any]): Unit = {
            state.lazySet(null)   // avoid leaks

            val cb = conts.pop()
            val ec = ctxs.peek()

            ec execute { () =>

              e match {
                case Left(t) => cb(false, t)
                case Right(a) => cb(true, a)
              }
            }
          }

          val next = cur.k { e =>
            if (!done.getAndSet(true)) {
              if (state.getAndSet(e) != null) {    // registration already completed, we're good to go
                continue(e)
              }
            }
          }

          conts push { (b, ar) =>
            if (!b) {
              if (!done.getAndSet(true)) {
                // if we get an error before the callback, then propagate
                val cb = conts.pop()
                cb(b, ar)
              }
            } else {
              if (state.compareAndSet(null, Left(null))) {
                ar.asInstanceOf[Option[IO[Unit]]] match {
                  case Some(cancelToken) =>
                    // TODO register handler

                  case None => ()
                }
              } else {
                // the callback was invoked before registration
                continue(state.get())
              }
            }
          }

          runLoop(next, ctxs, conts)

        // ReadEC
        case 4 =>
          conts.pop()(true, ctxs.peek())
          runLoop(null, ctxs, conts)

        case 5 =>
          val cur = cur0.asInstanceOf[EvalOn[Any]]

          ctxs.push(cur.ec)

          conts push { (b, ar) =>
            ctxs.pop()
            conts.pop()(b, ar)
          }

          runLoop(cur.ioa, ctxs, conts)

        case 6 =>
          val cur = cur0.asInstanceOf[Map[Any, Any]]

          // NB: this means that repeated map is stack-unsafe
          conts push { (b, ar) =>
            val cb = conts.pop()

            if (b) {
              try {
                cb(true, cur.f(ar))
              } catch {
                case NonFatal(t) =>
                  cb(false, t)
              }
            } else {
              cb(b, ar)
            }
          }

          runLoop(cur.ioe, ctxs, conts)

        case 7 =>
          val cur = cur0.asInstanceOf[FlatMap[Any, Any]]

          conts push { (b, ar) =>
            if (b) {
              try {
                heapCur = cur.f(ar)
              } catch {
                case NonFatal(t) =>
                  conts.pop()(false, t)
              }
            } else {
              conts.pop()(b, ar)    // I think this means error handling is stack-unsafe
            }
          }

          runLoop(cur.ioe, ctxs, conts)

        case 8 =>
          val cur = cur0.asInstanceOf[HandleErrorWith[Any]]

          conts push { (b, ar) =>
            if (b)
              conts.pop()(b, ar)    // if it's *not* an error, just pass it along
            else
              heapCur = cur.f(ar.asInstanceOf[Throwable])
          }

          runLoop(cur.ioa, ctxs, conts)
      }
    }
  }
}
