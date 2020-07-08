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

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

/*
 * Rationale on memory barrier exploitation in this class...
 *
 * The runloop is held by a single thread at any moment in
 * time. This is ensured by the `suspended` AtomicBoolean,
 * which is set to `true` when evaluation of an `Async` causes
 * us to semantically block. Releasing the runloop can thus
 * only be done by passing through a write barrier (on `suspended`),
 * and relocating that runloop can itself only be achieved by
 * passing through that same read/write barrier (a CAS on
 * `suspended`).
 *
 * Separate from this, the runloop may be *relocated* to a different
 * thread – for example, when evaluating Cede. When this happens,
 * we pass through a read/write barrier within the Executor as
 * we enqueue the action to restart the runloop, and then again
 * when that action is dequeued on the new thread. This ensures
 * that everything is appropriately published.
 *
 * By this argument, the `conts` stack is non-volatile and can be
 * safely implemented with an array. It is only accessed by one
 * thread at a time (so there are no atomicity concerns), and it
 * only becomes relevant to another thread after passing through
 * either an executor or the `suspended` gate, both of which
 * would ensure safe publication of writes. `ctxs`, `currentCtx`,
 * `masks`, `objectState`, and `booleanState` are all subject to
 * similar arguments. `cancel` and `join` are only made visible
 * by the Executor read/write barriers, but their writes are
 * merely a fast-path and are not necessary for correctness.
 *
 * TODO! There are two concurrency issues in this implementation.
 * First, `canceled` is non-volatile. It can be set by the self
 * fiber in the evaluation of `Canceled`, and this is obviously
 * not problematic in any way. However, when it is set by another
 * fiber, it may not be immediately visible. The canceling fiber
 * will pass through a read/write barrier on `suspended` immediately
 * after setting, and subsequently a read/write barrier in the
 * Executor if the CAS fails, and thus `canceled` will be published
 * to memory, but the *canceled* fiber will not pass through a read
 * barrier on `suspended`, or anything else, until it hits an async
 * boundary (where it would set `suspended`) or a Cede, where it
 * would pass through the Executor. This means that cancelation of
 * an active runloop has lower granularity than we would like. This
 * is more a question of performance than correctness, since
 * cancelation is a hint.
 *
 * The second concurrency issue is more severe. `callbacks` may be
 * modified by multiple threads simultaneously in a case where
 * several fibers `join` on a single fiber at once. This race is
 * correctly handled in the case of the very first joiner and the
 * second joiner, but a race between the nth joiner and the n+1th
 * joiner where n > 1 will *not* be correctly handled, since it
 * will result in a simultaneous `push` on `SafeArrayStack`.
 */
private[effect] final class IOFiber[A](name: String, timer: UnsafeTimer, initMask: Int) extends Fiber[IO, Throwable, A] {
  import IO._

  // I would rather have these on the stack, but we can't because we sometimes need to relocate our runloop to another fiber
  private[this] var conts: ArrayStack[IOCont] = _

  // fast-path to head
  private[this] var currentCtx: ExecutionContext = _
  private[this] var ctxs: ArrayStack[ExecutionContext] = _

  // TODO a non-volatile cancel bit is very unlikely to be observed, in practice, until we hit an async boundary
  private[this] var canceled: Boolean = false

  private[this] var masks: Int = _
  private[this] val finalizers = new ArrayStack[Outcome[IO, Throwable, Any] => IO[Unit]](16)    // TODO reason about whether or not the final finalizers are visible here

  // TODO SafeArrayStack isn't safe enough here since multiple threads may push at once
  // (Outcome[IO, Throwable, A] => Unit) | SafeArrayStack[Outcome[IO, Throwable, A] => Unit]
  private[this] val callback: AtomicReference[AnyRef] = new AtomicReference()

  // true when semantically blocking (ensures that we only unblock *once*)
  private[this] val suspended: AtomicBoolean = new AtomicBoolean(false)

  // TODO we may be able to weaken this to just a @volatile
  private[this] val outcome: AtomicReference[Outcome[IO, Throwable, A]] =
    new AtomicReference()

  private[this] val objectState = new ArrayStack[AnyRef](16)
  private[this] val booleanState = new BooleanArrayStack(16)

  private[this] val childCount = IOFiber.childCount

  // pre-fetching of all continuations (to avoid memory barriers)
  private[this] val CancelationLoopK = IOFiber.CancelationLoopK
  private[this] val CancelationLoopNodoneK = IOFiber.CancelationLoopNodoneK
  private[this] val RunTerminusK = IOFiber.RunTerminusK
  private[this] val AsyncK = IOFiber.AsyncK
  private[this] val EvalOnK = IOFiber.EvalOnK
  private[this] val MapK = IOFiber.MapK
  private[this] val FlatMapK = IOFiber.FlatMapK
  private[this] val HandleErrorWithK = IOFiber.HandleErrorWithK
  private[this] val OnCaseK = IOFiber.OnCaseK
  private[this] val OnCaseForwarderK = IOFiber.OnCaseForwarderK
  private[this] val UncancelableK = IOFiber.UncancelableK
  private[this] val UnmaskK = IOFiber.UnmaskK

  // similar prefetch for Outcome
  private[this] val OutcomeCanceled = IOFiber.OutcomeCanceled
  private[this] val OutcomeErrored = IOFiber.OutcomeErrored
  private[this] val OutcomeCompleted = IOFiber.OutcomeCompleted

  // similar prefetch for AsyncState
  private[this] val AsyncStateInitial = AsyncState.Initial
  private[this] val AsyncStateRegisteredNoFinalizer = AsyncState.RegisteredNoFinalizer
  private[this] val AsyncStateRegisteredWithFinalizer = AsyncState.RegisteredWithFinalizer

  def this(timer: UnsafeTimer, cb: Outcome[IO, Throwable, A] => Unit, initMask: Int) = {
    this("main", timer, initMask)
    callback.set(cb)
  }

  var cancel: IO[Unit] = {
    val prelude = IO {
      canceled = true
      cancel = IO.unit
    }

    val completion = IO(suspended.compareAndSet(true, false)).ifM(
      IO {
        // println(s"<$name> running cancelation (finalizers.length = ${finalizers.unsafeIndex()})")

        val oc = OutcomeCanceled.asInstanceOf[Outcome[IO, Throwable, Nothing]]
        if (outcome.compareAndSet(null, oc.asInstanceOf[Outcome[IO, Throwable, A]])) {
          done(oc.asInstanceOf[Outcome[IO, Throwable, A]])

          if (!finalizers.isEmpty()) {
            conts = new ArrayStack[IOCont](16)
            conts.push(CancelationLoopK)

            masks += 1
            runLoop(finalizers.pop()(oc.asInstanceOf[Outcome[IO, Throwable, Any]]))
          }
        }
      },
      join.void)    // someone else is in charge of the runloop; wait for them to cancel

    prelude *> completion
  }

  // this is swapped for an IO.pure(outcome.get()) when we complete
  var join: IO[Outcome[IO, Throwable, A]] =
    IO async { cb =>
      IO {
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

  private[effect] def run(cur: IO[Any], ec: ExecutionContext, masks: Int): Unit = {
    conts = new ArrayStack[IOCont](16)
    conts.push(RunTerminusK)

    ctxs = new ArrayStack[ExecutionContext](2)
    currentCtx = ec
    ctxs.push(ec)

    this.masks = masks
    runLoop(cur)
  }

  private def done(oc: Outcome[IO, Throwable, A]): Unit = {
    println(s"<$name> invoking done($oc); callback = ${callback.get()}")
    join = IO.pure(oc)

    val cb0 = callback.get()
    try {
      if (cb0 == null) {
        // println(s"<$name> completed with empty callback")
      } else if (cb0.isInstanceOf[Function1[_, _]]) {
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
      }
    } finally {
      callback.set(null)    // avoid leaks
    }
  }

  private def asyncContinue(state: AtomicReference[AsyncState], e: Either[Throwable, Any]): Unit = {
    state.lazySet(AsyncStateInitial)   // avoid leaks

    val ec = currentCtx

    if (!canceled || masks != initMask) {
      execute(ec) { () =>
        val next = e match {
          case Left(t) => failed(t, 0)
          case Right(a) => conts.pop()(this, true, a, 0)
        }

        runLoop(next)
      }
    }
  }

  // masks encoding: initMask => no masks, ++ => push, -- => pop
  private def runLoop(cur0: IO[Any]): Unit = {
    if (canceled && masks == initMask) {
      // println(s"<$name> running cancelation (finalizers.length = ${finalizers.unsafeIndex()})")

      // this code is (mostly) redundant with Fiber#cancel for purposes of TCO
      val oc = OutcomeCanceled.asInstanceOf[Outcome[IO, Throwable, A]]
      if (outcome.compareAndSet(null, oc)) {
        done(oc)

        if (!finalizers.isEmpty()) {
          conts = new ArrayStack[IOCont](16)
          conts.push(CancelationLoopNodoneK)

          // suppress all subsequent cancelation on this fiber
          masks += 1
          runLoop(finalizers.pop()(oc.asInstanceOf[Outcome[IO, Throwable, Any]]))
        }
      }
    } else {
      println(s"<$name> looping on $cur0")

      // cur0 will be null when we're semantically blocked
      if (!conts.isEmpty() && cur0 != null) {
        (cur0.tag: @switch) match {
          case 0 =>
            val cur = cur0.asInstanceOf[Pure[Any]]
            runLoop(conts.pop()(this, true, cur.value, 0))

          case 1 =>
            val cur = cur0.asInstanceOf[Delay[Any]]

            var success = false
            val r = try {
              val r = cur.thunk()
              success = true
              r
            } catch {
              case NonFatal(t) => t
            }

            runLoop {
              if (success)
                conts.pop()(this, true, r, 0)
              else
                failed(r, 0)
            }

          case 2 =>
            val cur = cur0.asInstanceOf[Error]
            runLoop(failed(cur.t, 0))

          case 3 =>
            val cur = cur0.asInstanceOf[Async[Any]]

            val done = new AtomicBoolean()

            /*
             * The purpose of this buffer is to ensure that registration takes
             * primacy over callbacks in the event that registration produces
             * errors in sequence. This implements a "queueing" semantic to
             * the callbacks, implying that the callback is sequenced after
             * registration has fully completed, giving us deterministic
             * serialization.
             */
            val state = new AtomicReference[AsyncState](AsyncStateInitial)

            objectState.push(done)
            objectState.push(state)

            val next = cur.k { e =>
              // println(s"<$name> callback run with $e")
              if (!done.getAndSet(true)) {
                val old = state.getAndSet(AsyncState.Complete(e))

                /*
                 * We *need* to own the runloop when we return, so we CAS loop
                 * on suspended to break the race condition in AsyncK where
                 * `state` is set but suspend() has not yet run. If `state` is
                 * set then `suspend()` should be right behind it *unless* we
                 * have been canceled. If we were canceled, then some other
                 * fiber is taking care of our finalizers and will have
                 * marked suspended as false, meaning that `canceled` will be set
                 * to true. Either way, we won't own the runloop.
                 */
                @tailrec
                def loop(): Unit = {
                  if (suspended.compareAndSet(true, false)) {
                    // double-check canceled. if it's true, then we were *already* canceled while suspended and all our finalizers already ran
                    if (!canceled) {
                      if (old == AsyncStateRegisteredWithFinalizer) {
                        // we completed and were not canceled, so we pop the finalizer
                        // note that we're safe to do so since we own the runloop
                        finalizers.pop()
                      }

                      asyncContinue(state, e)
                    }
                  } else if (!canceled) {
                    loop()
                  }
                }

                if (old != AsyncStateInitial) {    // registration already completed, we're good to go
                  loop()
                }
              }
            }

            conts.push(AsyncK)

            runLoop(next)

          // ReadEC
          case 4 =>
            runLoop(conts.pop()(this, true, currentCtx, 0))

          case 5 =>
            val cur = cur0.asInstanceOf[EvalOn[Any]]

            val ec = cur.ec
            currentCtx = ec
            ctxs.push(ec)
            conts.push(EvalOnK)

            execute(ec) { () =>
              runLoop(cur.ioa)
            }

          case 6 =>
            val cur = cur0.asInstanceOf[Map[Any, Any]]

            objectState.push(cur.f)
            conts.push(MapK)

            runLoop(cur.ioe)

          case 7 =>
            val cur = cur0.asInstanceOf[FlatMap[Any, Any]]

            objectState.push(cur.f)
            conts.push(FlatMapK)

            runLoop(cur.ioe)

          case 8 =>
            val cur = cur0.asInstanceOf[HandleErrorWith[Any]]

            objectState.push(cur.f)
            conts.push(HandleErrorWithK)

            runLoop(cur.ioa)

          case 9 =>
            val cur = cur0.asInstanceOf[OnCase[Any]]

            val ec = currentCtx
            finalizers push { oc =>
              val iou = try {
                cur.f(oc)
              } catch {
                case NonFatal(e) => IO.unit
              }

              if (ec eq currentCtx)
                iou
              else
                EvalOn(iou, ec)
            }
            // println(s"pushed onto finalizers: length = ${finalizers.unsafeIndex()}")

            conts.push(OnCaseK)

            runLoop(cur.ioa)

          case 10 =>
            val cur = cur0.asInstanceOf[Uncancelable[Any]]

            masks += 1
            val id = masks
            val poll = new (IO ~> IO) {
              def apply[B](ioa: IO[B]) = IO.Unmask(ioa, id)
            }

            conts.push(UncancelableK)

            runLoop(cur.body(poll))

          // Canceled
          case 11 =>
            canceled = true
            if (masks != initMask)
              runLoop(conts.pop()(this, true, (), 0))
            else
              runLoop(null)   // trust the cancelation check at the start of the loop

          case 12 =>
            val cur = cur0.asInstanceOf[Start[Any]]

            val childName = s"start-${childCount.getAndIncrement()}"

            // flip masking to negative and push it forward one tick to avoid potential conflicts with current fiber construction
            val initMask2 = if (masks != Int.MaxValue)
              -(masks + 1)
            else
              masks + 1   // will overflow

            val fiber = new IOFiber(
              childName,
              timer,
              initMask2)

            // println(s"<$name> spawning <$childName>")

            val ec = currentCtx
            execute(ec)(() => fiber.run(cur.ioa, ec, initMask2))

            runLoop(conts.pop()(this, true, fiber, 0))

          case 13 =>
            // TODO self-cancelation within a nested poll could result in deadlocks in `both`
            // example: uncancelable(p => F.both(fa >> p(canceled) >> fc, fd)).
            // when we check cancelation in the parent fiber, we are using the masking at the point of racePair, rather than just trusting the masking at the point of the poll
            val cur = cur0.asInstanceOf[RacePair[Any, Any]]

            val next = IO.async[Either[(Any, Fiber[IO, Throwable, Any]), (Fiber[IO, Throwable, Any], Any)]] { cb =>
              IO {
                val fiberA = new IOFiber[Any](s"racePair-left-${childCount.getAndIncrement()}", timer, initMask)
                val fiberB = new IOFiber[Any](s"racePair-right-${childCount.getAndIncrement()}", timer, initMask)

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
                      runLoop(null)
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

                val ec = currentCtx

                execute(ec)(() => fiberA.run(cur.ioa, ec, masks))
                execute(ec)(() => fiberB.run(cur.iob, ec, masks))

                Some(fiberA.cancel *> fiberB.cancel)
              }
            }

            runLoop(next)

          case 14 =>
            val cur = cur0.asInstanceOf[Sleep]

            runLoop(IO.async[Unit] { cb =>
              IO {
                val cancel = timer.sleep(cur.delay, () => cb(Right(())))
                Some(IO(cancel.run()))
              }
            })

          // RealTime
          case 15 =>
            runLoop(conts.pop()(this, true, timer.nowMillis().millis, 0))

          // Monotonic
          case 16 =>
            runLoop(conts.pop()(this, true, timer.monotonicNanos().nanos, 0))

          // Cede
          case 17 =>
            currentCtx execute { () =>
              // println("continuing from cede ")

              runLoop(conts.pop()(this, true, (), 0))
            }

          case 18 =>
            val cur = cur0.asInstanceOf[Unmask[Any]]

            if (masks == cur.id) {
              masks -= 1
            }

            conts.push(UnmaskK)

            runLoop(cur.ioa)
        }
      }
    }
  }

  // we use these forwarders because direct field access (private[this]) is faster
  private def isCanceled(): Boolean =
    canceled

  private def currentOutcome(): Outcome[IO, Throwable, A] =
    outcome.get()

  private def casOutcome(old: Outcome[IO, Throwable, A], value: Outcome[IO, Throwable, A]): Boolean =
    outcome.compareAndSet(old, value)

  private def hasFinalizers(): Boolean =
    !finalizers.isEmpty()

  private def pushFinalizer(f: Outcome[IO, Throwable, Any] => IO[Unit]): Unit =
    finalizers.push(f)

  private def popFinalizer(): Outcome[IO, Throwable, Any] => IO[Unit] =
    finalizers.pop()

  private def pushCont(cont: IOCont): Unit =
    conts.push(cont)

  private def popCont(): IOCont =
    conts.pop()

  private def pushObjectState(state: AnyRef): Unit =
    objectState.push(state)

  private def popObjectState(): AnyRef =
    objectState.pop()

  private def pushBooleanState(state: Boolean): Unit =
    booleanState.push(state)

  private def popBooleanState(): Boolean =
    booleanState.pop()

  private def isUnmasked(): Boolean =
    masks == initMask

  private def pushMask(): Unit =
    masks += 1

  private def popMask(): Unit =
    masks -= 1

  private def suspend(): Unit = suspended.set(true)

  // returns the *new* context, not the old
  private def popContext(): ExecutionContext = {
    ctxs.pop()
    val ec = ctxs.peek()
    currentCtx = ec
    ec
  }

  private def failed(error: Any, depth: Int): IO[Any] = {
    // println(s"<$name> failed() with $error")
    val buffer = conts.unsafeBuffer()

    var i = conts.unsafeIndex() - 1
    val orig = i
    var k: IOCont = null

    while (i >= 0 && k == null) {
      if ((buffer(i) eq FlatMapK) || (buffer(i) eq MapK))
        i -= 1
      else
        k = buffer(i).asInstanceOf[IOCont]
    }

    conts.unsafeSet(i)
    objectState.unsafeSet(objectState.unsafeIndex() - (orig - i))

    k(this, false, error, depth)
  }

  private def execute(ec: ExecutionContext)(action: Runnable): Unit = {
    try {
      ec.execute(action)
    } catch {
      case _: RejectedExecutionException => ()    // swallow this exception, since it means we're being externally murdered, so we should just... drop the runloop
    }
  }
}

private object IOFiber {

  private val MaxStackDepth = 512

  private val childCount = new AtomicInteger(0)

  // prefetch
  private final val OutcomeCanceled = Outcome.Canceled()
  private final val OutcomeErrored = Outcome.Errored
  private final val OutcomeCompleted = Outcome.Completed

  ////////////////////////////////////////////////
  // preallocation of all necessary continuations
  ////////////////////////////////////////////////

  private object CancelationLoopK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      val oc = currentOutcome()

      if (hasFinalizers()) {
        pushCont(this)
        runLoop(popFinalizer()(oc.asInstanceOf[Outcome[IO, Throwable, Any]]))
      } else {
        done(oc.asInstanceOf[Outcome[IO, Throwable, A]])
      }

      null
    }
  }

  private object CancelationLoopNodoneK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      if (hasFinalizers()) {
        pushCont(this)
        runLoop(popFinalizer()(currentOutcome().asInstanceOf[Outcome[IO, Throwable, Any]]))
      }

      null
    }
  }

  private object RunTerminusK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self.{isCanceled, done, currentOutcome, casOutcome}

      if (isCanceled())   // this can happen if we don't check the canceled flag before completion
        casOutcome(null, OutcomeCanceled.asInstanceOf[Outcome[IO, Throwable, A]])
      else if (success)
        casOutcome(null, OutcomeCompleted(IO.pure(result.asInstanceOf[A])))
      else
        casOutcome(null, OutcomeErrored(result.asInstanceOf[Throwable]))

      done(currentOutcome())

      null
    }
  }

  private object AsyncK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      val state = popObjectState().asInstanceOf[AtomicReference[AsyncState]]
      val done = popObjectState().asInstanceOf[AtomicBoolean]

      if (!success) {
        if (!done.getAndSet(true)) {
          // if we get an error before the callback, then propagate
          failed(result, depth + 1)
        } else {
          // we got the error *after* the callback, but we have queueing semantics
          // therefore, side-channel the callback results
          // println(state.get())

          asyncContinue(
            state,
            Left(result.asInstanceOf[Throwable]))

          null
        }
      } else {
        if (isUnmasked()) {
          result.asInstanceOf[Option[IO[Unit]]] match {
            case Some(cancelToken) =>
              pushFinalizer(_.fold(cancelToken, _ => IO.unit, _ => IO.unit))

              // indicate the presence of the cancel token by pushing Right instead of Left
              if (!state.compareAndSet(AsyncStateInitial, AsyncStateRegisteredWithFinalizer)) {
                // the callback was invoked before registration
                popFinalizer()
                asyncContinue(state, state.get().result)
              } else {
                suspend()
              }

            case None =>
              if (!state.compareAndSet(AsyncStateInitial, AsyncStateRegisteredNoFinalizer)) {
                // the callback was invoked before registration
                asyncContinue(state, state.get().result)
              } else {
                suspend()
              }
          }
        } else {
          // if we're masked, then don't even bother registering the cancel token
          if (!state.compareAndSet(AsyncStateInitial, AsyncStateRegisteredNoFinalizer)) {
            // the callback was invoked before registration
            asyncContinue(state, state.get().result)
          } else {
            suspend()
          }
        }

        null
      }
    }
  }

  private object EvalOnK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      val ec = popContext()

      // special cancelation check to ensure we don't accidentally fork the runloop here
      if (!isCanceled() || !isUnmasked()) {
        execute(ec) { () =>
          if (success)
            runLoop(popCont()(self, true, result, 0))
          else
            runLoop(failed(result, 0))
        }
      }

      null
    }
  }

  // NB: this means repeated map is stack-unsafe
  private object MapK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      val f = popObjectState().asInstanceOf[Any => Any]

      var success = false
      var transformed = try {
        val back = f(result)
        success = true
        back
      } catch {
        case NonFatal(t) => t
      }

      if (depth > MaxStackDepth) {
        if (success)
          IO.Pure(transformed)
        else
          IO.Error(transformed.asInstanceOf[Throwable])
      } else {
        if (success)
          popCont()(self, true, transformed, depth + 1)
        else
          failed(transformed, depth + 1)
      }
    }
  }

  private object FlatMapK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      val f = popObjectState().asInstanceOf[Any => IO[Any]]

      try {
        f(result)
      } catch {
        case NonFatal(t) => failed(t, depth + 1)
      }
    }
  }

  private object HandleErrorWithK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      val f = popObjectState().asInstanceOf[Throwable => IO[Any]]

      if (success) {
        // if it's *not* an error, just pass it along
        if (depth > MaxStackDepth)
          IO.Pure(result)
        else
          popCont()(self, true, result, depth + 1)
      } else {
        try {
          f(result.asInstanceOf[Throwable])
        } catch {
          case NonFatal(t) => failed(t, depth + 1)
        }
      }
    }
  }

  private object OnCaseK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      val oc: Outcome[IO, Throwable, Any] = if (success)
        OutcomeCompleted(IO.pure(result))
      else
        OutcomeErrored(result.asInstanceOf[Throwable])

      // println(s"popping from finalizers: length = ${finalizers.unsafeIndex()}")
      val f = popFinalizer()
      // println(s"continuing onCase with $result ==> ${f(oc)}")

      val back = f(oc)

      pushBooleanState(success)
      pushObjectState(result.asInstanceOf[AnyRef])

      pushCont(OnCaseForwarderK)

      back
    }
  }

  private object OnCaseForwarderK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._
      if (popBooleanState())
        popCont()(self, true, popObjectState(), depth + 1)
      else
        failed(popObjectState(), depth + 1)
    }
  }

  private object UncancelableK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      popMask()
      if (success)
        popCont()(self, true, result, depth + 1)
      else
        failed(result, depth + 1)
    }
  }

  private object UnmaskK extends IOCont {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      pushMask()
      if (success)
        popCont()(self, true, result, depth + 1)
      else
        failed(result, depth + 1)
    }
  }
}
