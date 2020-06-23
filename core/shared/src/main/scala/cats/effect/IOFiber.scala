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

import java.util.concurrent.atomic.AtomicBoolean

private[effect] final class IOFiber[A](heapCur0: IO[A], name: String) extends Fiber[IO, Throwable, A] {
  import IO._

  private[this] var heapCur: IO[Any] = heapCur0
  private[this] var canceled: Boolean = false

  final def cancel: IO[Unit] = Delay(() => canceled = true)

  // this needs to push handlers onto the *tail* of the continuation stack...
  final def join: IO[Outcome[IO, Throwable, A]] = ???

  @tailrec
  private[effect] def runLoop(
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

    if (!conts.isEmpty()) {
      (cur0.tag: @switch) match {
        case 0 =>
          val cur = cur0.asInstanceOf[Pure[Any]]

          conts.pop()(true, cur.value)
          runLoop(null, ctxs, conts)

        case 1 =>
          val cur = cur0.asInstanceOf[Delay[Any]]

          val cb = conts.pop()
          try {
            cb(true, cur.thunk)
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

          val cb = conts.pop()
          val ec = ctxs.peek()
          val done = new AtomicBoolean()

          val next = cur.k { e =>
            if (!done.getAndSet(true)) {
              ec execute { () =>
                e match {
                  case Left(t) => cb(false, t)
                  case Right(a) => cb(true, a)
                }
              }
            }
          }

          // TODO cancelation handling? (in a continuation)

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
