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

import cats.~>
import cats.implicits._

import scala.annotation.{switch, tailrec}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

private[effect] final class IOFiber[A](name: String, timer: UnsafeTimer) extends Fiber[IO, Throwable, A] {
  import IO._

  // I would rather have this on the stack, but we can't because we sometimes need to relocate our runloop to another fiber
  private[this] var ctxs: ArrayStack[ExecutionContext] = _

  private[this] var heapCur: IO[Any] = _
  private[this] var canceled: Boolean = false

  // TODO optimize to int or something
  private var masks = new ArrayStack[AnyRef](2)
  private[this] val finalizers = new ArrayStack[Outcome[IO, Throwable, Any] => IO[Unit]](16)    // TODO reason about whether or not the final finalizers are visible here

  // (Outcome[IO, Throwable, A] => Unit) | SafeArrayStack[Outcome[IO, Throwable, A] => Unit]
  private[this] val callback: AtomicReference[AnyRef] = new AtomicReference()

  // true when semantically blocking (ensures that we only unblock *once*)
  private[this] val suspended: AtomicBoolean = new AtomicBoolean(false)

  private[this] val outcome: AtomicReference[Outcome[IO, Throwable, A]] =
    new AtomicReference()

  def this(heapCur0: IO[A], timer: UnsafeTimer, cb: Outcome[IO, Throwable, A] => Unit) = {
    this("main", timer)
    heapCur = heapCur0
    callback.set(cb)
  }

  def this(heapCur0: IO[A], name: String, timer: UnsafeTimer) = {
    this(name, timer)
    heapCur = heapCur0
  }

  val cancel: IO[Unit] =
    IO { canceled = true } >>
      IO(suspended.compareAndSet(true, false)).ifM(
        IO {
          // println(s"<$name> running cancelation (finalizers.length = ${finalizers.unsafeIndex()})")

          val oc: Outcome[IO, Throwable, Nothing] = Outcome.Canceled()
          if (outcome.compareAndSet(null, oc.asInstanceOf[Outcome[IO, Throwable, A]])) {
            if (finalizers.isEmpty()) {
              done(oc.asInstanceOf[Outcome[IO, Throwable, A]])
            } else {
              masks.push(new AnyRef)    // suppress all subsequent cancelation on this fiber

              val conts = new ArrayStack[(Boolean, Any) => Unit](16)    // TODO tune!

              def loop(b: Boolean, ar: Any): Unit = {
                if (!finalizers.isEmpty()) {
                  conts.push(loop)
                  runLoop(finalizers.pop()(oc.asInstanceOf[Outcome[IO, Throwable, Any]]), conts)
                } else {
                  done(oc.asInstanceOf[Outcome[IO, Throwable, A]])
                }
              }

              conts.push(loop)

              runLoop(finalizers.pop()(oc.asInstanceOf[Outcome[IO, Throwable, Any]]), conts)
            }
          }
        },
        join.void)    // someone else is in charge of the runloop; wait for them to cancel

  val join: IO[Outcome[IO, Throwable, A]] =
    IO async { cb =>
      IO {
        // println(s"joining on $name")

        registerListener(oc => cb(Right(oc)))
        None    // TODO maybe we can unregister the listener? (if we don't, it's probably a memory leak via the enclosing async)
      }
    }

  private def registerListener(listener: Outcome[IO, Throwable, A] => Unit): Unit = {
    if (outcome.get() == null) {
      @tailrec
      def loop(): Unit = {
        if (callback.get() == null) {
          if (!callback.compareAndSet(null, listener)) {
            loop()
          }
        } else {
          val old0 = callback.get()
          if (old0.isInstanceOf[Function1[_, _]]) {
            val old = old0.asInstanceOf[Outcome[IO, Throwable, A] => Unit]

            val stack = new SafeArrayStack[Outcome[IO, Throwable, A] => Unit](4)
            stack.push(old)
            stack.push(listener)

            if (!callback.compareAndSet(old, stack)) {
              loop()
            }
          } else {
            val stack = old0.asInstanceOf[SafeArrayStack[Outcome[IO, Throwable, A] => Unit]]
            stack.push(listener)
          }
        }
      }

      loop()

      // double-check
      if (outcome.get() != null) {
        listener(outcome.get())    // the implementation of async saves us from double-calls
      }
    } else {
      listener(outcome.get())
    }
  }

  private[effect] def run(ec: ExecutionContext): Unit = {
    val cur = heapCur
    heapCur = null

    ctxs = new ArrayStack[ExecutionContext](2)
    ctxs.push(ec)

    val conts = new ArrayStack[(Boolean, Any) => Unit](16)    // TODO tune!

    conts push { (b, ar) =>
      if (canceled)   // this can happen if we don't check the canceled flag before completion
        outcome.compareAndSet(null, Outcome.Canceled())
      else if (b)
        outcome.compareAndSet(null, Outcome.Completed(IO.pure(ar.asInstanceOf[A])))
      else
        outcome.compareAndSet(null, Outcome.Errored(ar.asInstanceOf[Throwable]))

      done(outcome.get())
    }

    runLoop(cur, conts)
  }

  private[this] def done(oc: Outcome[IO, Throwable, A]): Unit = {
    // println(s"invoking done($oc); callback = ${callback.get()}")

    val cb0 = callback.get()
    if (cb0.isInstanceOf[Function1[_, _]]) {
      val cb = cb0.asInstanceOf[Outcome[IO, Throwable, A] => Unit]
      cb(oc)
    } else if (cb0.isInstanceOf[SafeArrayStack[_]]) {
      val stack = cb0.asInstanceOf[SafeArrayStack[AnyRef]]

      val bound = stack.unsafeIndex()
      val buffer = stack.unsafeBuffer()

      var i = 0
      while (i < bound) {
        buffer(i).asInstanceOf[Outcome[IO, Throwable, A] => Unit](oc)
        i += 1
      }

      callback.set(null)    // avoid leaks
    }
  }

  private[this] def runLoop(cur00: IO[Any], conts: ArrayStack[(Boolean, Any) => Unit]): Unit = {
    if (canceled && masks.isEmpty()) {
      // println(s"<$name> running cancelation (finalizers.length = ${finalizers.unsafeIndex()})")

      // this code is (mostly) redundant with Fiber#cancel for purposes of TCO
      val oc: Outcome[IO, Throwable, Nothing] = Outcome.Canceled()
      if (outcome.compareAndSet(null, oc.asInstanceOf[Outcome[IO, Throwable, A]])) {
        heapCur = null

        if (finalizers.isEmpty()) {
          done(oc.asInstanceOf[Outcome[IO, Throwable, A]])
        } else {
          masks.push(new AnyRef)    // suppress all subsequent cancelation on this fiber

          val conts = new ArrayStack[(Boolean, Any) => Unit](16)    // TODO tune!

          def loop(b: Boolean, ar: Any): Unit = {
            if (!finalizers.isEmpty()) {
              conts.push(loop)
              runLoop(finalizers.pop()(oc.asInstanceOf[Outcome[IO, Throwable, Any]]), conts)
            } else {
              done(oc.asInstanceOf[Outcome[IO, Throwable, A]])
            }
          }

          conts.push(loop)

          runLoop(finalizers.pop()(oc.asInstanceOf[Outcome[IO, Throwable, Any]]), conts)
        }
      }
    } else {
      val cur0 = if (cur00 == null) {
        val back = heapCur
        heapCur = null
        back
      } else {
        cur00
      }

      // println(s"<$name> looping on $cur0")

      // cur0 will be null when we're semantically blocked
      if (!conts.isEmpty() && cur0 != null) {
        (cur0.tag: @switch) match {
          case 0 =>
            val cur = cur0.asInstanceOf[Pure[Any]]

            conts.pop()(true, cur.value)
            runLoop(null, conts)

          case 1 =>
            val cur = cur0.asInstanceOf[Delay[Any]]

            val cb = conts.pop()
            try {
              cb(true, cur.thunk())
            } catch {
              case NonFatal(t) =>
                cb(false, t)
            }

            runLoop(null, conts)

          case 2 =>
            val cur = cur0.asInstanceOf[Error]

            conts.pop()(false, cur.t)
            runLoop(null, conts)

          case 3 =>
            val cur = cur0.asInstanceOf[Async[Any]]

            val done = new AtomicBoolean()

            /*
             * Four states here:
             *
             * - null (no one has completed, or everyone has)
             * - Left(null) (registration has completed without cancelToken but not callback)
             * - Right(null) (registration has completed with cancelToken but not callback)
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

              if (!canceled || !masks.isEmpty()) {
                ec execute { () =>
                  e match {
                    case Left(t) => cb(false, t)
                    case Right(a) => cb(true, a)
                  }

                  runLoop(null, conts)
                }
              }
            }

            val next = cur.k { e =>
              // println(s"<$name> callback run with $e")
              // do an extra cancel check here just to preemptively avoid timer load
              if (!done.getAndSet(true) && !(canceled && masks.isEmpty())) {
                val s = state.getAndSet(e)
                if (s != null && suspended.compareAndSet(true, false)) {    // registration already completed, we're good to go
                  if (s.isRight) {
                    // we completed and were not canceled, so we pop the finalizer
                    // note that we're safe to do so since we own the runloop
                    finalizers.pop()
                  }

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
                } else {
                  // we got the error *after* the callback, but we have queueing semantics
                  // therefore, side-channel the callback results
                  // println(state.get())

                  continue(Left(ar.asInstanceOf[Throwable]))
                }
              } else {
                if (masks.isEmpty()) {
                  ar.asInstanceOf[Option[IO[Unit]]] match {
                    case Some(cancelToken) =>
                      finalizers.push(_.fold(cancelToken, _ => IO.unit, _ => IO.unit))

                      // indicate the presence of the cancel token by pushing Right instead of Left
                      if (!state.compareAndSet(null, Right(null))) {
                        // the callback was invoked before registration
                        finalizers.pop()
                        continue(state.get())
                      } else {
                        suspended.set(true)
                      }

                    case None =>
                      if (!state.compareAndSet(null, Left(null))) {
                        // the callback was invoked before registration
                        continue(state.get())
                      } else {
                        suspended.set(true)
                      }
                  }
                } else {
                  // if we're masked, then don't even bother registering the cancel token
                  if (!state.compareAndSet(null, Left(null))) {
                    // the callback was invoked before registration
                    continue(state.get())
                  } else {
                    suspended.set(true)
                  }
                }
              }
            }

            runLoop(next, conts)

          // ReadEC
          case 4 =>
            conts.pop()(true, ctxs.peek())
            runLoop(null, conts)

          case 5 =>
            val cur = cur0.asInstanceOf[EvalOn[Any]]

            ctxs.push(cur.ec)

            conts push { (b, ar) =>
              ctxs.pop()

              // special cancelation check to ensure we don't accidentally fork the runloop here
              if (!canceled || !masks.isEmpty()) {
                ctxs.peek() execute { () =>
                  conts.pop()(b, ar)
                  runLoop(null, conts)
                }
              }
            }

            cur.ec execute { () =>
              runLoop(cur.ioa, conts)
            }

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

            runLoop(cur.ioe, conts)

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

            runLoop(cur.ioe, conts)

          case 8 =>
            val cur = cur0.asInstanceOf[HandleErrorWith[Any]]

            conts push { (b, ar) =>
              if (b)
                conts.pop()(b, ar)    // if it's *not* an error, just pass it along
              else
                heapCur = cur.f(ar.asInstanceOf[Throwable])
            }

            runLoop(cur.ioa, conts)

          case 9 =>
            val cur = cur0.asInstanceOf[OnCase[Any]]

            val ec = ctxs.peek()
            finalizers push { oc =>
              val iou = try {
                cur.f(oc)
              } catch {
                case NonFatal(e) => IO.unit
              }

              if (ec eq ctxs.peek())
                iou
              else
                EvalOn(iou, ec)
            }
            // println(s"pushed onto finalizers: length = ${finalizers.unsafeIndex()}")

            conts push { (b, ar) =>
              val oc: Outcome[IO, Throwable, Any] = if (b)
                Outcome.Completed(IO.pure(ar))
              else
                Outcome.Errored(ar.asInstanceOf[Throwable])

              // println(s"popping from finalizers: length = ${finalizers.unsafeIndex()}")
              val f = finalizers.pop()
              // println(s"continuing onCase with $ar ==> ${f(oc)}")

              heapCur = f(oc)

              conts push { (_, _) =>
                conts.pop()(b, ar)
              }
            }

            runLoop(cur.ioa, conts)

          case 10 =>
            val cur = cur0.asInstanceOf[Uncancelable[Any]]

            val id = new AnyRef
            val poll = new (IO ~> IO) {
              def apply[B](ioa: IO[B]) = IO.Unmask(ioa, id)
            }

            masks.push(id)
            conts push { (b, ar) =>
              masks.pop()
              conts.pop()(b, ar)
            }

            runLoop(cur.body(poll), conts)

          // Canceled
          case 11 =>
            canceled = true
            if (!masks.isEmpty()) {
              conts.pop()(true, ())
            }
            runLoop(null, conts)

          case 12 =>
            val cur = cur0.asInstanceOf[Start[Any]]

            val childName = s"start-${IOFiber.childCount.getAndIncrement()}"
            val fiber = new IOFiber(
              cur.ioa,
              childName,
              timer)

            // println(s"<$name> spawning <$childName>")

            val ec = ctxs.peek()
            ec.execute(() => fiber.run(ec))

            conts.pop()(true, fiber)
            runLoop(null, conts)

          case 13 =>
            val cur = cur0.asInstanceOf[RacePair[Any, Any]]

            val next = IO.async[Either[(Any, Fiber[IO, Throwable, Any]), (Fiber[IO, Throwable, Any], Any)]] { cb =>
              IO {
                val fiberA = new IOFiber(cur.ioa, s"racePair-left-${IOFiber.childCount.getAndIncrement()}", timer)
                val fiberB = new IOFiber(cur.iob, s"racePair-right-${IOFiber.childCount.getAndIncrement()}", timer)

                fiberA.masks = masks.copy()
                fiberB.masks = masks.copy()

                val firstError = new AtomicReference[Throwable](null)
                val firstCanceled = new AtomicBoolean(false)

                def listener(left: Boolean)(oc: Outcome[IO, Throwable, Any]): Unit = {
                  // println(s"listener fired (left = $left; oc = $oc)")

                  if (oc.isInstanceOf[Outcome.Completed[IO, Throwable, Any]]) {
                    val result = oc.asInstanceOf[Outcome.Completed[IO, Throwable, Any]].fa.asInstanceOf[IO.Pure[Any]].value

                    val wrapped = if (left)
                      Left((result, fiberB))
                    else
                      Right((fiberA, result))

                    cb(Right(wrapped))
                  } else if (oc.isInstanceOf[Outcome.Errored[IO, Throwable, Any]]) {
                    val error = oc.asInstanceOf[Outcome.Errored[IO, Throwable, Any]].e

                    if (!firstError.compareAndSet(null, error)) {
                      // we were the second to error, so report back
                      // TODO side-channel the error in firstError.get()
                      cb(Left(error))
                    } else {
                      // we were the first to error, double check to see if second is canceled and report
                      if (firstCanceled.get()) {
                        cb(Left(error))
                      }
                    }
                  } else {
                    if (!firstCanceled.compareAndSet(false, true)) {
                      // both are canceled, and we're the second, then cancel the outer fiber
                      canceled = true
                      // this is tricky, but since we forward our masks to our child, we *know* that we aren't masked, so the runLoop will just immediately run the finalizers for us
                      runLoop(null, conts)
                    } else {
                      val error = firstError.get()
                      if (error != null) {
                        // we were canceled, and the other errored, so use its error
                        cb(Left(error))
                      }
                    }
                  }
                }

                fiberA.registerListener(listener(true))
                fiberB.registerListener(listener(false))

                val ec = ctxs.peek()

                ec.execute(() => fiberA.run(ec))
                ec.execute(() => fiberB.run(ec))

                Some(fiberA.cancel *> fiberB.cancel)
              }
            }

            runLoop(next, conts)

          case 14 =>
            val cur = cur0.asInstanceOf[Sleep]

            runLoop(IO.async[Unit] { cb =>
              IO {
                val cancel = timer.sleep(cur.delay, () => cb(Right(())))
                Some(IO(cancel.run()))
              }
            }, conts)

          // RealTime
          case 15 =>
            conts.pop()(true, timer.nowMillis().millis)
            runLoop(null, conts)

          // Monotonic
          case 16 =>
            conts.pop()(true, timer.monotonicNanos().nanos)
            runLoop(null, conts)

          // Cede
          case 17 =>
            ctxs.peek() execute { () =>
              // println("continuing from cede ")

              conts.pop()(true, ())
              runLoop(null, conts)
            }

          case 18 =>
            val cur = cur0.asInstanceOf[Unmask[Any]]

            if (masks.peek() eq cur.id) {
              masks.pop()
            }

            conts push { (b, ar) =>
              masks.push(cur.id)
              conts.pop()(b, ar)
            }

            runLoop(cur.ioa, conts)
        }
      }
    }
  }
}

private object IOFiber {
  private val childCount = new AtomicInteger(0)
}
