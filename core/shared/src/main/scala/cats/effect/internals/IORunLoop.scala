/*
 * Copyright 2017 Typelevel
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

package cats.effect.internals

import cats.effect.IO
import cats.effect.IO.{Async, Bind, Pure, RaiseError, Suspend}
import scala.annotation.tailrec
import scala.collection.mutable.ArrayStack

private[effect] object IORunLoop {
  /** Evaluates the given `IO` reference, calling the given callback
    * with the result when completed.
    */
  def start[A](source: IO[A], cb: Either[Throwable, A] => Unit): Unit =
    loop(source, cb.asInstanceOf[Callback], null, null, null)

  /** Evaluates the given `IO` reference until an asynchronous
    * boundary is hit.
    */
  def step[A](source: IO[A]): IO[A] =
    step(source, null, null).asInstanceOf[IO[A]]

  private type Current = IO[Any]
  private type Bind = Any => IO[Any]
  private type CallStack = ArrayStack[Bind]
  private type Callback = Either[Throwable, Any] => Unit

  /** Tail-recursive loop that evaluates an `IO` reference.
    *
    * Note `rcb`, `bFirst` and `bRest` ARE nullable, because
    * initialization is avoided until the last possible moment to
    * reduce pressure on heap memory.
    */
  @tailrec private def loop(
    source: Current,
    cb: Callback,
    rcb: RestartCallback,
    bFirst: Bind,
    bRest: CallStack): Unit = {

    source match {
      case ref @ Bind(fa, _, _) =>
        var callStack: CallStack = bRest
        val bindNext = ref.function

        if (bFirst ne null) {
          if (callStack eq null) callStack = new ArrayStack()
          callStack.push(bFirst)
        }
        // Next iteration please
        loop(fa, cb, rcb, bindNext, callStack)

      case Pure(value) =>
        popNextBind(bFirst, bRest) match {
          case null => cb(Right(value))
          case bind =>
            val fa = try bind(value) catch { case NonFatal(ex) => RaiseError(ex) }
            // Next iteration please
            loop(fa, cb, rcb, null, bRest)
        }

      case Suspend(thunk) =>
        // Next iteration please
        val fa = try thunk() catch { case NonFatal(ex) => RaiseError(ex) }
        loop(fa, cb, rcb, bFirst, bRest)

      case RaiseError(ex) =>
        findErrorHandler(bFirst, bRest) match {
          case null => cb(Left(ex))
          case bind =>
            val fa = try bind.recover(ex) catch { case NonFatal(e) => RaiseError(e) }
            // Next cycle please
            loop(fa, cb, rcb, null, bRest)
        }

      case Async(register) =>
        val restartCallback = if (rcb != null) rcb else RestartCallback(cb)
        restartCallback.prepare(bFirst, bRest)
        register(restartCallback)
    }
  }

  /** A [[loop]] variant that evaluates the given `IO` reference
    * until the first async boundary, or until the final result,
    * whichever comes first.
    *
    * Note `bFirst` and `bRest` are nullable references, in order
    * to avoid initialization until the last possible moment.
    */
  @tailrec private def step(
    source: Current,
    bFirst: Bind,
    bRest: CallStack): IO[Any] = {

    source match {
      case ref @ Bind(fa, _, _) =>
        var callStack: CallStack = bRest
        val bindNext = ref.function

        if (bFirst ne null) {
          if (callStack eq null) callStack = new ArrayStack()
          callStack.push(bFirst)
        }
        // Next iteration please
        step(fa, bindNext, callStack)

      case ref @ Pure(value) =>
        popNextBind(bFirst, bRest) match {
          case null => ref
          case bind =>
            val fa = try bind(value) catch {
              case NonFatal(ex) => RaiseError(ex)
            }
            // Next iteration please
            step(fa, null, bRest)
        }

      case Suspend(thunk) =>
        val fa = try thunk() catch {
          case NonFatal(ex) => RaiseError(ex)
        }
        // Next iteration please
        step(fa, bFirst, bRest)

      case ref @ RaiseError(ex) =>
        findErrorHandler(bFirst, bRest) match {
          case null => ref
          case bind =>
            val fa = try bind.recover(ex) catch {
              case NonFatal(e) => RaiseError(e)
            }
            // Next cycle please
            step(fa, null, bRest)
        }

      case Async(register) =>
        // Hitting an async boundary means we have to stop, however
        // if we had previous `flatMap` operations prior to this, then
        // we need to resume the loop with the collected stack
        if (bFirst != null || (bRest != null && bRest.nonEmpty))
          Async[Any] { cb =>
            val rcb = RestartCallback(cb)
            rcb.prepare(bFirst, bRest)
            register(rcb)
          }
        else
          source
    }
  }

  /** Pops the next bind function from the stack, but filters out
    * `Mapping.OnError` references, because we know they won't do
    * anything — an optimization for `handleError`.
    */
  private def popNextBind(bFirst: Bind, bRest: CallStack): Bind = {
    if ((bFirst ne null) && !bFirst.isInstanceOf[IOFrame.ErrorHandler[_]])
      bFirst
    else if (bRest != null) {
      var cursor: Bind = null
      while (cursor == null && bRest.nonEmpty) {
        val ref = bRest.pop()
        if (!ref.isInstanceOf[IOFrame.ErrorHandler[_]]) cursor = ref
      }
      cursor
    } else {
      null
    }
  }

  /** Finds a [[IOFrame]] capable of handling errors in our bind
    * call-stack, invoked after a `RaiseError` is observed.
    */
  private def findErrorHandler(bFirst: Bind, bRest: CallStack): IOFrame[Any, IO[Any]] = {
    var result: IOFrame[Any, IO[Any]] = null
    var cursor = bFirst
    var continue = true

    while (continue) {
      if (cursor != null && cursor.isInstanceOf[IOFrame[_, _]]) {
        result = cursor.asInstanceOf[IOFrame[Any, IO[Any]]]
        continue = false
      } else {
        cursor = if (bRest != null && bRest.nonEmpty) bRest.pop() else null
        continue = cursor != null
      }
    }
    result
  }

  /** A `RestartCallback` gets created only once, per [[start]]
    * (`unsafeRunAsync`) invocation, once an `Async` state is hit,
    * its job being to resume the loop after the boundary, but with
    * the bind call-stack restored
    *
    * This is a trick the implementation is using to avoid creating
    * extraneous callback references on asynchronous boundaries, in
    * order to reduce memory pressure.
    *
    * It's an ugly, mutable implementation.
    * For internal use only, here be dragons!
    */
  private final class RestartCallback private (cb: Callback)
    extends Callback {

    private[this] var canCall = false
    private[this] var bFirst: Bind = _
    private[this] var bRest: CallStack = _

    def prepare(bFirst: Bind, bRest: CallStack): Unit = {
      canCall = true
      this.bFirst = bFirst
      this.bRest = bRest
    }

    def apply(either: Either[Throwable, Any]): Unit =
      if (canCall) {
        canCall = false
        either match {
          case Right(value) =>
            loop(Pure(value), cb, this, bFirst, bRest)
          case Left(e) =>
            loop(RaiseError(e), cb, this, bFirst, bRest)
        }
      }
  }

  private object RestartCallback {
    /** Builder that avoids double wrapping. */
    def apply(cb: Callback): RestartCallback =
      cb match {
        case ref: RestartCallback => ref
        case _ => new RestartCallback(cb)
      }
  }
}
