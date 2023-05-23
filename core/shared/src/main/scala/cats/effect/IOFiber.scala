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

import cats.arrow.FunctionK
import cats.effect.tracing._
import cats.effect.unsafe._

import scala.annotation.{switch, tailrec}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Rationale on memory barrier exploitation in this class...
 *
 * This class extends `java.util.concurrent.atomic.AtomicBoolean`
 * (through `IOFiberPlatform`) in order to forego the allocation
 * of a separate `AtomicBoolean` object. All credit goes to
 * Viktor Klang.
 * https://viktorklang.com/blog/Futures-in-Scala-2.12-part-8.html
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
 */
private final class IOFiber[A](
    initState: IOLocalState,
    cb: OutcomeIO[A] => Unit,
    startIO: IO[A],
    startEC: ExecutionContext,
    rt: IORuntime
) extends IOFiberPlatform[A]
    with FiberIO[A]
    with Runnable {
  /* true when fiber blocking (ensures that we only unblock *once*) */
  suspended: AtomicBoolean =>

  import IOFiber._
  import IO.{println => _, _}
  import IOFiberConstants._
  import TracingConstants._

  private[this] var localState: IOLocalState = initState
  private[this] var currentCtx: ExecutionContext = startEC
  private[this] val objectState: ArrayStack[AnyRef] = ArrayStack()
  private[this] val finalizers: ArrayStack[IO[Unit]] = ArrayStack()
  private[this] val callbacks: CallbackStack[OutcomeIO[A]] = CallbackStack(cb)
  private[this] var resumeTag: Byte = ExecR
  private[this] var resumeIO: IO[Any] = startIO
  private[this] val runtime: IORuntime = rt
  private[this] val tracingEvents: RingBuffer =
    if (TracingConstants.isStackTracing) RingBuffer.empty(runtime.traceBufferLogSize) else null

  /*
   * Ideally these would be on the stack, but they can't because we sometimes need to
   * relocate our runloop to another fiber.
   */
  private[this] var conts: ByteStack = _

  private[this] var canceled: Boolean = false
  private[this] var masks: Int = 0
  private[this] var finalizing: Boolean = false

  @volatile
  private[this] var outcome: OutcomeIO[A] = _

  override def run(): Unit = {
    // insert a read barrier after every async boundary
    readBarrier()
    (resumeTag: @switch) match {
      case 0 => execR()
      case 1 => asyncContinueSuccessfulR()
      case 2 => asyncContinueFailedR()
      case 3 => asyncContinueCanceledR()
      case 4 => asyncContinueCanceledWithFinalizerR()
      case 5 => blockingR()
      case 6 => cedeR()
      case 7 => autoCedeR()
      case 8 => () // DoneR
    }
  }

  /* backing fields for `cancel` and `join` */

  /* this is swapped for an `IO.unit` when we complete */
  private[this] var _cancel: IO[Unit] = IO uncancelable { _ =>
    canceled = true

    // println(s"${name}: attempting cancelation")

    /* check to see if the target fiber is suspended */
    if (resume()) {
      /* ...it was! was it masked? */
      if (isUnmasked()) {
        /* ...nope! take over the target fiber's runloop and run the finalizers */
        // println(s"<$name> running cancelation (finalizers.length = ${finalizers.unsafeIndex()})")

        /* if we have async finalizers, runLoop may return early */
        IO.async_[Unit] { fin =>
          // println(s"${name}: canceller started at ${Thread.currentThread().getName} + ${suspended.get()}")
          val ec = currentCtx
          resumeTag = AsyncContinueCanceledWithFinalizerR
          objectState.push(fin)
          scheduleFiber(ec, this)
        }
      } else {
        /*
         * it was masked, so we need to wait for it to finish whatever
         * it was doing  and cancel itself
         */
        suspend() /* allow someone else to take the runloop */
        join.void
      }
    } else {
      // println(s"${name}: had to join")
      /* it's already being run somewhere; await the finalizers */
      join.void
    }
  }

  /* this is swapped for an `IO.pure(outcome)` when we complete */
  private[this] var _join: IO[OutcomeIO[A]] = IO.async { cb =>
    IO {
      val stack = registerListener(oc => cb(Right(oc)))

      if (stack eq null)
        Some(IO.unit) /* we were already invoked, so no `CallbackStack` needs to be managed */
      else {
        val handle = stack.currentHandle()
        Some(IO(stack.clearCurrent(handle)))
      }
    }
  }

  def cancel: IO[Unit] = {
    // The `_cancel` field is published in terms of the `suspended` atomic variable.
    readBarrier()
    _cancel
  }

  def join: IO[OutcomeIO[A]] = {
    // The `_join` field is published in terms of the `suspended` atomic variable.
    readBarrier()
    _join
  }

  /* masks encoding: initMask => no masks, ++ => push, -- => pop */
  @tailrec
  private[this] def runLoop(
      _cur0: IO[Any],
      cancelationIterations: Int,
      autoCedeIterations: Int): Unit = {
    /*
     * `cur` will be set to `EndFiber` when the runloop needs to terminate,
     * either because the entire IO is done, or because this branch is done
     * and execution is continuing asynchronously in a different runloop invocation.
     */
    if (_cur0 eq IO.EndFiber) {
      return
    }

    var nextCancelation = cancelationIterations - 1
    var nextAutoCede = autoCedeIterations
    if (nextCancelation <= 0) {
      // Ensure that we see cancelation.
      readBarrier()
      nextCancelation = runtime.cancelationCheckThreshold
      // automatic yielding threshold is always a multiple of the cancelation threshold
      nextAutoCede -= nextCancelation

      if (nextAutoCede <= 0) {
        resumeTag = AutoCedeR
        resumeIO = _cur0
        val ec = currentCtx
        rescheduleFiber(ec, this)
        return
      }
    }

    if (shouldFinalize()) {
      val fin = prepareFiberForCancelation(null)
      runLoop(fin, nextCancelation, nextAutoCede)
    } else {
      /* Null IO, blow up but keep the failure within IO */
      val cur0: IO[Any] = if (_cur0 == null) {
        IO.Error(new NullPointerException())
      } else {
        _cur0
      }

      // System.out.println(s"looping on $cur0")
      /*
       * The cases have to use continuous constants to generate a `tableswitch`.
       * Do not name or reorder them.
       */
      (cur0.tag: @switch) match {
        case 0 =>
          val cur = cur0.asInstanceOf[Pure[Any]]
          runLoop(succeeded(cur.value, 0), nextCancelation, nextAutoCede)

        case 1 =>
          val cur = cur0.asInstanceOf[Error]
          runLoop(failed(cur.t, 0), nextCancelation, nextAutoCede)

        case 2 =>
          val cur = cur0.asInstanceOf[Delay[Any]]

          if (isStackTracing) {
            pushTracingEvent(cur.event)
          }

          var error: Throwable = null
          val r =
            try cur.thunk()
            catch {
              case t if NonFatal(t) =>
                error = t
              case t: Throwable =>
                onFatalFailure(t)
            }

          val next =
            if (error == null) succeeded(r, 0)
            else failed(error, 0)

          runLoop(next, nextCancelation, nextAutoCede)

        /* RealTime */
        case 3 =>
          runLoop(
            succeeded(runtime.scheduler.nowMicros().micros, 0),
            nextCancelation,
            nextAutoCede)

        /* Monotonic */
        case 4 =>
          runLoop(
            succeeded(runtime.scheduler.monotonicNanos().nanos, 0),
            nextCancelation,
            nextAutoCede)

        /* ReadEC */
        case 5 =>
          runLoop(succeeded(currentCtx, 0), nextCancelation, nextAutoCede)

        case 6 =>
          val cur = cur0.asInstanceOf[Map[Any, Any]]

          if (isStackTracing) {
            pushTracingEvent(cur.event)
          }

          val ioe = cur.ioe
          val f = cur.f

          def next(v: Any): IO[Any] = {
            var error: Throwable = null
            val result =
              try f(v)
              catch {
                case t if NonFatal(t) =>
                  error = t
                case t: Throwable =>
                  onFatalFailure(t)
              }

            if (error == null) succeeded(result, 0) else failed(error, 0)
          }

          (ioe.tag: @switch) match {
            case 0 =>
              val pure = ioe.asInstanceOf[Pure[Any]]
              runLoop(next(pure.value), nextCancelation - 1, nextAutoCede)

            case 1 =>
              val error = ioe.asInstanceOf[Error]
              runLoop(failed(error.t, 0), nextCancelation - 1, nextAutoCede)

            case 2 =>
              val delay = ioe.asInstanceOf[Delay[Any]]

              if (isStackTracing) {
                pushTracingEvent(delay.event)
              }

              // this code is inlined in order to avoid two `try` blocks
              var error: Throwable = null
              val result =
                try f(delay.thunk())
                catch {
                  case t if NonFatal(t) =>
                    error = t
                  case t: Throwable =>
                    onFatalFailure(t)
                }

              val nextIO = if (error == null) succeeded(result, 0) else failed(error, 0)
              runLoop(nextIO, nextCancelation - 1, nextAutoCede)

            case 3 =>
              val realTime = runtime.scheduler.nowMicros().micros
              runLoop(next(realTime), nextCancelation - 1, nextAutoCede)

            case 4 =>
              val monotonic = runtime.scheduler.monotonicNanos().nanos
              runLoop(next(monotonic), nextCancelation - 1, nextAutoCede)

            case 5 =>
              val ec = currentCtx
              runLoop(next(ec), nextCancelation - 1, nextAutoCede)

            case _ =>
              objectState.push(f)
              conts = ByteStack.push(conts, MapK)
              runLoop(ioe, nextCancelation, nextAutoCede)
          }

        case 7 =>
          val cur = cur0.asInstanceOf[FlatMap[Any, Any]]

          if (isStackTracing) {
            pushTracingEvent(cur.event)
          }

          val ioe = cur.ioe
          val f = cur.f

          def next(v: Any): IO[Any] =
            try f(v)
            catch {
              case t if NonFatal(t) =>
                failed(t, 0)
              case t: Throwable =>
                onFatalFailure(t)
            }

          (ioe.tag: @switch) match {
            case 0 =>
              val pure = ioe.asInstanceOf[Pure[Any]]
              runLoop(next(pure.value), nextCancelation - 1, nextAutoCede)

            case 1 =>
              val error = ioe.asInstanceOf[Error]
              runLoop(failed(error.t, 0), nextCancelation - 1, nextAutoCede)

            case 2 =>
              val delay = ioe.asInstanceOf[Delay[Any]]

              if (isStackTracing) {
                pushTracingEvent(delay.event)
              }

              // this code is inlined in order to avoid two `try` blocks
              val result =
                try f(delay.thunk())
                catch {
                  case t if NonFatal(t) =>
                    failed(t, 0)
                  case t: Throwable =>
                    onFatalFailure(t)
                }

              runLoop(result, nextCancelation - 1, nextAutoCede)

            case 3 =>
              val realTime = runtime.scheduler.nowMicros().micros
              runLoop(next(realTime), nextCancelation - 1, nextAutoCede)

            case 4 =>
              val monotonic = runtime.scheduler.monotonicNanos().nanos
              runLoop(next(monotonic), nextCancelation - 1, nextAutoCede)

            case 5 =>
              val ec = currentCtx
              runLoop(next(ec), nextCancelation - 1, nextAutoCede)

            case _ =>
              objectState.push(f)
              conts = ByteStack.push(conts, FlatMapK)
              runLoop(ioe, nextCancelation, nextAutoCede)
          }

        case 8 =>
          val cur = cur0.asInstanceOf[Attempt[Any]]

          val ioa = cur.ioa

          (ioa.tag: @switch) match {
            case 0 =>
              val pure = ioa.asInstanceOf[Pure[Any]]
              runLoop(succeeded(Right(pure.value), 0), nextCancelation - 1, nextAutoCede)

            case 1 =>
              val error = ioa.asInstanceOf[Error]
              val t = error.t
              // We need to augment the exception here because it doesn't get
              // forwarded to the `failed` path.
              Tracing.augmentThrowable(runtime.enhancedExceptions, t, tracingEvents)
              runLoop(succeeded(Left(t), 0), nextCancelation - 1, nextAutoCede)

            case 2 =>
              val delay = ioa.asInstanceOf[Delay[Any]]

              if (isStackTracing) {
                pushTracingEvent(delay.event)
              }

              // this code is inlined in order to avoid two `try` blocks
              var error: Throwable = null
              val result =
                try delay.thunk()
                catch {
                  case t if NonFatal(t) =>
                    // We need to augment the exception here because it doesn't
                    // get forwarded to the `failed` path.
                    Tracing.augmentThrowable(runtime.enhancedExceptions, t, tracingEvents)
                    error = t
                  case t: Throwable =>
                    onFatalFailure(t)
                }

              val next =
                if (error == null) succeeded(Right(result), 0) else succeeded(Left(error), 0)
              runLoop(next, nextCancelation - 1, nextAutoCede)

            case 3 =>
              val realTime = runtime.scheduler.nowMicros().micros
              runLoop(succeeded(Right(realTime), 0), nextCancelation - 1, nextAutoCede)

            case 4 =>
              val monotonic = runtime.scheduler.monotonicNanos().nanos
              runLoop(succeeded(Right(monotonic), 0), nextCancelation - 1, nextAutoCede)

            case 5 =>
              val ec = currentCtx
              runLoop(succeeded(Right(ec), 0), nextCancelation - 1, nextAutoCede)

            case _ =>
              conts = ByteStack.push(conts, AttemptK)
              runLoop(ioa, nextCancelation, nextAutoCede)
          }

        case 9 =>
          val cur = cur0.asInstanceOf[HandleErrorWith[Any]]

          if (isStackTracing) {
            pushTracingEvent(cur.event)
          }

          objectState.push(cur.f)
          conts = ByteStack.push(conts, HandleErrorWithK)

          runLoop(cur.ioa, nextCancelation, nextAutoCede)

        /* Canceled */
        case 10 =>
          canceled = true
          if (isUnmasked()) {
            /* run finalizers immediately */
            val fin = prepareFiberForCancelation(null)
            runLoop(fin, nextCancelation, nextAutoCede)
          } else {
            runLoop(succeeded((), 0), nextCancelation, nextAutoCede)
          }

        case 11 =>
          val cur = cur0.asInstanceOf[OnCancel[Any]]

          finalizers.push(EvalOn(cur.fin, currentCtx))
          // println(s"pushed onto finalizers: length = ${finalizers.unsafeIndex()}")

          /*
           * the OnCancelK marker is used by `succeeded` to remove the
           * finalizer when `ioa` completes uninterrupted.
           */
          conts = ByteStack.push(conts, OnCancelK)
          runLoop(cur.ioa, nextCancelation, nextAutoCede)

        case 12 =>
          val cur = cur0.asInstanceOf[Uncancelable[Any]]

          if (isStackTracing) {
            pushTracingEvent(cur.event)
          }

          masks += 1
          val id = masks
          val poll = new Poll[IO] {
            def apply[B](ioa: IO[B]) = IO.Uncancelable.UnmaskRunLoop(ioa, id, IOFiber.this)
          }

          val next =
            try cur.body(poll)
            catch {
              case t if NonFatal(t) =>
                IO.raiseError(t)
              case t: Throwable =>
                onFatalFailure(t)
            }

          /*
           * The uncancelableK marker is used by `succeeded` and `failed`
           * to unmask once body completes.
           */
          conts = ByteStack.push(conts, UncancelableK)
          runLoop(next, nextCancelation, nextAutoCede)

        case 13 =>
          val cur = cur0.asInstanceOf[Uncancelable.UnmaskRunLoop[Any]]
          val self = this

          /*
           * we keep track of nested uncancelable sections.
           * The outer block wins.
           */
          if (masks == cur.id && (self eq cur.self)) {
            masks -= 1
            /*
             * The UnmaskK marker gets used by `succeeded` and `failed`
             * to restore masking state after `cur.ioa` has finished
             */
            conts = ByteStack.push(conts, UnmaskK)
          }

          runLoop(cur.ioa, nextCancelation, nextAutoCede)

        case 14 =>
          val cur = cur0.asInstanceOf[IOCont[Any, Any]]

          /*
           * Takes `cb` (callback) and `get` and returns an IO that
           * uses them to embed async computations.
           * This is a CPS'd encoding that uses higher-rank
           * polymorphism to statically forbid concurrent operations
           * on `get`, which are unsafe since `get` closes over the
           * runloop.
           *
           */
          val body = cur.body

          if (isStackTracing) {
            pushTracingEvent(cur.event)
          }

          /*
           *`get` and `cb` (callback) race over the runloop.
           * If `cb` finishes after `get`, `get` just terminates by
           * suspending, and `cb` will resume the runloop via
           * `asyncContinue`.
           *
           * If `get` wins, it gets the result from the `state`
           * `AtomicReference` and it continues, while the callback just
           * terminates (`stateLoop`, when `(tag ne null) && (tag ne waiting)`)
           *
           * The two sides communicate with each other through
           * `state` to know who should take over, and through
           * `suspended` (which is manipulated via suspend and
           * resume), to negotiate ownership of the runloop.
           *
           * In case of interruption, neither side will continue,
           * and they will negotiate ownership with `cancel` to decide who
           * should run the finalisers (i.e. call `asyncCancel`).
           *
           */
          val state = new ContState(finalizing)

          val cb: Either[Throwable, Any] => Unit = { e =>
            // if someone called `cb` with `null`,
            // we'll pretend it's an NPE:
            val result = if (e eq null) {
              Left(new NullPointerException())
            } else {
              e
            }

            /*
             * We *need* to own the runloop when we return, so we CAS loop
             * on `suspended` (via `resume`) to break the race condition where
             * `state` has been set by `get, `but `suspend()` has not yet run.
             * If `state` is set then `suspend()` should be right behind it
             * *unless* we have been canceled.
             *
             * If we were canceled, `cb`, `cancel` and `get` are in a 3-way race
             * to run the finalizers.
             */
            @tailrec
            def loop(): Unit = {
              // println(s"cb loop sees suspended ${suspended.get} on fiber $name")
              /* try to take ownership of the runloop */
              if (resume()) {
                // `resume()` is a volatile read of `suspended` through which
                // `wasFinalizing` and `handle` are published
                if (finalizing == state.wasFinalizing) {
                  if (isStackTracing) {
                    state.handle.deregister()
                  }

                  val ec = currentCtx
                  if (!shouldFinalize()) {
                    /* we weren't canceled or completed, so schedule the runloop for execution */
                    result match {
                      case Left(t) =>
                        resumeTag = AsyncContinueFailedR
                        objectState.push(t)
                      case Right(a) =>
                        resumeTag = AsyncContinueSuccessfulR
                        objectState.push(a.asInstanceOf[AnyRef])
                    }
                  } else {
                    /*
                     * we were canceled, but since we have won the race on `suspended`
                     * via `resume`, `cancel` cannot run the finalisers, and we have to.
                     */
                    resumeTag = AsyncContinueCanceledR
                  }
                  scheduleFiber(ec, this)
                } else {
                  /*
                   * we were canceled while suspended, then our finalizer suspended,
                   * then we hit this line, so we shouldn't own the runloop at all
                   */
                  suspend()
                }
              } else if (finalizing == state.wasFinalizing && !shouldFinalize() && outcome == null) {
                /*
                 * If we aren't canceled or completed, and we're
                 * still in the same finalization state, loop on
                 * `suspended` to wait until `get` has released
                 * ownership of the runloop.
                 */
                loop()
              }

              /*
               * If we are canceled or completed or in hte process of finalizing
               * when we previously weren't, just die off and let `cancel` or `get`
               * win the race to `resume` and run the finalisers.
               */
            }

            val waiting = state.waiting

            /*
             * CAS loop to update the Cont state machine:
             * null - initial
             * waiting - (Get) waiting
             * anything else - (Cb) result
             *
             * If state is "initial" or "waiting", update the state,
             * and then if `get` has been flatMapped somewhere already
             * and is waiting for a result (i.e. it has suspended),
             * acquire runloop to continue.
             *
             * If not, `cb` arrived first, so it just sets the result and die off.
             *
             * If `state` is "result", the callback has been already invoked, so no-op
             * (guards from double calls).
             */
            @tailrec
            def stateLoop(): Unit = {
              val tag = state.get()
              if ((tag eq null) || (tag eq waiting)) {
                if (!state.compareAndSet(tag, result)) {
                  stateLoop()
                } else {
                  if (tag eq waiting) {
                    /*
                     * `get` has been sequenced and is waiting
                     * reacquire runloop to continue
                     */
                    loop()
                  }
                }
              }
            }

            stateLoop()
          }

          val get: IO[Any] = IOCont.Get(state)

          val next = body[IO].apply(cb, get, FunctionK.id)

          runLoop(next, nextCancelation, nextAutoCede)

        case 15 =>
          val cur = cur0.asInstanceOf[IOCont.Get[Any]]

          val state = cur.state

          /*
           * If get gets canceled but the result hasn't been computed yet,
           * restore the state to "initial" (null) to ensure a subsequent `Get` in
           * a finalizer still works with the same logic.
           */
          val fin = IO {
            state.compareAndSet(state.waiting, null)
            ()
          }
          finalizers.push(fin)
          conts = ByteStack.push(conts, OnCancelK)

          if (state.compareAndSet(null, state.waiting)) {
            /*
             * `state` was "initial" (null), so `get` has arrived before the callback,
             * it needs to set the state to "waiting" and suspend: `cb` will
             * resume with the result once that's ready
             */

            /*
             * we set the finalizing check to the *suspension* point, which may
             * be in a different finalizer scope than the cont itself.
             * `wasFinalizing` is published by a volatile store on `suspended`.
             */
            state.wasFinalizing = finalizing

            if (isStackTracing) {
              state.handle = monitor()
              finalizers.push(IO {
                state.handle.deregister()
                ()
              })
              // remove the above finalizer if the Get completes without getting cancelled
              conts = ByteStack.push(conts, OnCancelK)
            }

            /*
             * You should probably just read this as `suspended.compareAndSet(false, true)`.
             * This CAS should always succeed since we own the runloop,
             * but we need it in order to introduce a full memory barrier
             * which ensures we will always see the most up-to-date value
             * for `canceled` in `shouldFinalize`, ensuring no finalisation leaks
             */
            suspended.getAndSet(true)

            /*
             * race condition check: we may have been canceled
             * after setting the state but before we suspended
             */
            if (shouldFinalize()) {
              /*
               * if we can re-acquire the run-loop, we can finalize,
               * otherwise somebody else acquired it and will eventually finalize.
               *
               * In this path, `get`, the `cb` callback and `cancel`
               * all race via `resume` to decide who should run the
               * finalisers.
               */
              if (resume()) {
                if (shouldFinalize()) {
                  val fin = prepareFiberForCancelation(null)
                  runLoop(fin, nextCancelation, nextAutoCede)
                } else {
                  suspend()
                }
              }
            }
          } else {
            /*
             * State was no longer "initial" (null), as the CAS above failed; so the
             * callback has already been invoked and the state is "result".
             * We leave the "result" state unmodified so that `get` is idempotent.
             *
             * Note that it's impossible for `state` to be "waiting" here:
             * - `cont` doesn't allow concurrent calls to `get`, so there can't be
             *    another `get` in "waiting" when we execute this.
             *
             * - If a previous `get` happened before this code, and we are in a `flatMap`
             *   or `handleErrorWith`, it means the callback has completed once
             *   (unblocking the first `get` and letting us execute), and the state is still
             *   "result".
             *
             * - If a previous `get` has been canceled and we are being called within an
             *  `onCancel` of that `get`, the finalizer installed on the `Get` node by `Cont`
             *   has restored the state to "initial" before the execution of this method,
             *   which would have been caught by the previous branch unless the `cb` has
             *   completed and the state is "result"
             */

            val result = state.get()

            if (!shouldFinalize()) {
              /* we weren't canceled, so resume the runloop */
              val next = result match {
                case Left(t) => failed(t, 0)
                case Right(a) => succeeded(a, 0)
              }

              runLoop(next, nextCancelation, nextAutoCede)
            } else if (outcome == null) {
              /*
               * we were canceled, but `cancel` cannot run the finalisers
               * because the runloop was not suspended, so we have to run them
               */
              val fin = prepareFiberForCancelation(null)
              runLoop(fin, nextCancelation, nextAutoCede)
            }
          }

        /* Cede */
        case 16 =>
          resumeTag = CedeR
          rescheduleFiber(currentCtx, this)

        case 17 =>
          val cur = cur0.asInstanceOf[Start[Any]]

          val ec = currentCtx
          val fiber = new IOFiber[Any](
            localState,
            null,
            cur.ioa,
            ec,
            runtime
          )

          // println(s"<$name> spawning <$childName>")

          scheduleFiber(ec, fiber)

          runLoop(succeeded(fiber, 0), nextCancelation, nextAutoCede)

        case 18 =>
          val cur = cur0.asInstanceOf[RacePair[Any, Any]]

          val next =
            IO.async[Either[(OutcomeIO[Any], FiberIO[Any]), (FiberIO[Any], OutcomeIO[Any])]] {
              cb =>
                IO {
                  val ec = currentCtx
                  val rt = runtime

                  val fiberA = new IOFiber[Any](
                    localState,
                    null,
                    cur.ioa,
                    ec,
                    rt
                  )

                  val fiberB = new IOFiber[Any](
                    localState,
                    null,
                    cur.iob,
                    ec,
                    rt
                  )

                  fiberA.setCallback(oc => cb(Right(Left((oc, fiberB)))))
                  fiberB.setCallback(oc => cb(Right(Right((fiberA, oc)))))

                  scheduleFiber(ec, fiberA)
                  scheduleFiber(ec, fiberB)

                  val cancel =
                    for {
                      cancelA <- fiberA.cancel.start
                      cancelB <- fiberB.cancel.start
                      _ <- cancelA.join
                      _ <- cancelB.join
                    } yield ()

                  Some(cancel)
                }
            }

          runLoop(next, nextCancelation, nextAutoCede)

        case 19 =>
          val cur = cur0.asInstanceOf[Sleep]
          val delay = cur.delay

          val next =
            if (delay.length > 0)
              IO.async[Unit] { cb =>
                IO {
                  val scheduler = runtime.scheduler

                  val cancel =
                    if (scheduler.isInstanceOf[WorkStealingThreadPool])
                      scheduler.asInstanceOf[WorkStealingThreadPool].sleepInternal(delay, cb)
                    else
                      scheduler.sleep(delay, () => cb(RightUnit))

                  Some(IO(cancel.run()))
                }
              }
            else IO.cede

          runLoop(next, nextCancelation, nextAutoCede)

        case 20 =>
          val cur = cur0.asInstanceOf[EvalOn[Any]]

          /* fast-path when it's an identity transformation */
          if (cur.ec eq currentCtx) {
            runLoop(cur.ioa, nextCancelation, nextAutoCede)
          } else {
            val ec = cur.ec
            objectState.push(currentCtx)
            currentCtx = ec
            conts = ByteStack.push(conts, EvalOnK)

            resumeTag = AutoCedeR
            resumeIO = cur.ioa

            if (isStackTracing) {
              val handle = monitor()
              objectState.push(handle)
            }
            scheduleOnForeignEC(ec, this)
          }

        case 21 =>
          val cur = cur0.asInstanceOf[Blocking[Any]]
          /* we know we're on the JVM here */

          if (isStackTracing) {
            pushTracingEvent(cur.event)
          }

          if (cur.hint eq IOFiber.TypeBlocking) {
            val ec = currentCtx
            if (ec.isInstanceOf[WorkStealingThreadPool]) {
              val wstp = ec.asInstanceOf[WorkStealingThreadPool]
              if (wstp.canExecuteBlockingCode()) {
                var error: Throwable = null
                val r =
                  try {
                    scala.concurrent.blocking(cur.thunk())
                  } catch {
                    case t if NonFatal(t) =>
                      error = t
                    case t: Throwable =>
                      onFatalFailure(t)
                  }

                val next = if (error eq null) succeeded(r, 0) else failed(error, 0)
                runLoop(next, nextCancelation, nextAutoCede)
              } else {
                blockingFallback(cur)
              }
            } else {
              blockingFallback(cur)
            }
          } else {
            runLoop(interruptibleImpl(cur), nextCancelation, nextAutoCede)
          }

        case 22 =>
          val cur = cur0.asInstanceOf[Local[Any]]

          val (nextLocalState, value) = cur.f(localState)
          localState = nextLocalState
          runLoop(succeeded(value, 0), nextCancelation, nextAutoCede)

        case 23 =>
          runLoop(succeeded(Trace(tracingEvents), 0), nextCancelation, nextAutoCede)
      }
    }
  }

  private[this] def blockingFallback(cur: Blocking[Any]): Unit = {
    resumeTag = BlockingR
    resumeIO = cur

    if (isStackTracing) {
      val handle = monitor()
      objectState.push(handle)
    }

    val ec = runtime.blocking
    scheduleOnForeignEC(ec, this)
  }

  /*
   * Only the owner of the run-loop can invoke this.
   * Should be invoked at most once per fiber before termination.
   */
  private[this] def done(oc: OutcomeIO[A]): Unit = {
    // println(s"<$name> invoking done($oc); callback = ${callback.get()}")
    _join = IO.pure(oc)
    _cancel = IO.unit

    outcome = oc

    try {
      if (!callbacks(oc, false) && runtime.config.reportUnhandledFiberErrors) {
        oc match {
          case Outcome.Errored(e) => currentCtx.reportFailure(e)
          case _ => ()
        }
      }
    } finally {
      callbacks.clear() /* avoid leaks */
    }

    /*
     * need to reset masks to 0 to terminate async callbacks
     * in `cont` busy spinning in `loop` on the `!shouldFinalize` check.
     */
    masks = 0

    resumeTag = DoneR
    resumeIO = null
    suspended.set(false)

    /* clear out literally everything to avoid any possible memory leaks */

    conts = null
    objectState.invalidate()
    finalizers.invalidate()
    currentCtx = null

    if (isStackTracing) {
      tracingEvents.invalidate()
    }
  }

  /**
   * Overwrites the whole execution state of the fiber and prepares for executing finalizers
   * because cancelation has been triggered.
   */
  private[this] def prepareFiberForCancelation(cb: Either[Throwable, Unit] => Unit): IO[Any] = {
    if (!finalizers.isEmpty()) {
      if (!finalizing) {
        // Do not nuke the fiber execution state repeatedly.
        finalizing = true

        conts = ByteStack.create(8)
        conts = ByteStack.push(conts, CancelationLoopK)

        objectState.init(16)
        objectState.push(cb)

        /* suppress all subsequent cancelation on this fiber */
        masks += 1
      }

      // Return the first finalizer for execution.
      finalizers.pop()
    } else {
      // There are no finalizers to execute.

      // Unblock the canceler of this fiber.
      if (cb ne null) {
        cb(RightUnit)
      }

      // Unblock the joiners of this fiber.
      done(IOFiber.OutcomeCanceled.asInstanceOf[OutcomeIO[A]])

      // Exit from the run loop after this. The fiber is finished.
      IO.EndFiber
    }
  }

  /*
   * We should attempt finalization if all of the following are true:
   * 1) We own the runloop
   * 2) We have been canceled
   * 3) We are unmasked
   */
  private[this] def shouldFinalize(): Boolean =
    canceled && isUnmasked()

  private[this] def isUnmasked(): Boolean =
    masks == 0

  /*
   * You should probably just read this as `suspended.compareAndSet(true, false)`.
   * This implementation has the same semantics as the above, except that it guarantees
   * a write memory barrier in all cases, even when resumption fails. This in turn
   * makes it suitable as a publication mechanism (as we're using it).
   *
   * On x86, this should have almost exactly the same performance as a CAS even taking
   * into account the extra barrier (which x86 doesn't need anyway). On ARM without LSE
   * it should actually be *faster* because CAS isn't primitive but get-and-set is.
   */
  private[this] def resume(): Boolean =
    suspended.getAndSet(false)

  private[this] def suspend(): Unit =
    suspended.set(true)

  /**
   * Registers the suspended fiber in the global suspended fiber bag.
   */
  private[this] def monitor(): WeakBag.Handle = {
    runtime.fiberMonitor.monitorSuspended(this)
  }

  /**
   * Can only be correctly called on a fiber which has not started execution and was initially
   * created with a `null` callback, i.e. in `RacePair`.
   */
  private def setCallback(cb: OutcomeIO[A] => Unit): Unit = {
    callbacks.unsafeSetCallback(cb)
  }

  /* can return null, meaning that no CallbackStack needs to be later invalidated */
  private[this] def registerListener(
      listener: OutcomeIO[A] => Unit): CallbackStack[OutcomeIO[A]] = {
    if (outcome == null) {
      val back = callbacks.push(listener)

      /* double-check */
      if (outcome != null) {
        back.clearCurrent(back.currentHandle())
        listener(outcome) /* the implementation of async saves us from double-calls */
        null
      } else {
        back
      }
    } else {
      listener(outcome)
      null
    }
  }

  @tailrec
  private[this] def succeeded(result: Any, depth: Int): IO[Any] =
    (ByteStack.pop(conts): @switch) match {
      case 0 => // mapK
        val f = objectState.pop().asInstanceOf[Any => Any]

        var error: Throwable = null

        val transformed =
          try f(result)
          catch {
            case t if NonFatal(t) =>
              error = t
            case t: Throwable =>
              onFatalFailure(t)
          }

        if (depth > MaxStackDepth) {
          if (error == null) IO.Pure(transformed)
          else IO.Error(error)
        } else {
          if (error == null) succeeded(transformed, depth + 1)
          else failed(error, depth + 1)
        }

      case 1 => // flatMapK
        val f = objectState.pop().asInstanceOf[Any => IO[Any]]

        try f(result)
        catch {
          case t if NonFatal(t) =>
            failed(t, depth + 1)
          case t: Throwable =>
            onFatalFailure(t)
        }

      case 2 => cancelationLoopSuccessK()
      case 3 => runTerminusSuccessK(result)
      case 4 => evalOnSuccessK(result)

      case 5 => // handleErrorWithK
        // this is probably faster than the pre-scan we do in failed, since handlers are rarer than flatMaps
        objectState.pop()
        succeeded(result, depth)

      case 6 => // onCancelSuccessK
        finalizers.pop()
        succeeded(result, depth + 1)

      case 7 => // uncancelableSuccessK
        masks -= 1
        succeeded(result, depth + 1)

      case 8 => // unmaskSuccessK
        masks += 1
        succeeded(result, depth + 1)

      case 9 => // attemptK
        succeeded(Right(result), depth)
    }

  private[this] def failed(error: Throwable, depth: Int): IO[Any] = {
    Tracing.augmentThrowable(runtime.enhancedExceptions, error, tracingEvents)

    // println(s"<$name> failed() with $error")
    /*val buffer = conts.unsafeBuffer()

    var i = conts.unsafeIndex() - 1
    val orig = i
    var k: Byte = -1


     * short circuit on error by dropping map and flatMap continuations
     * until we hit a continuation that needs to deal with errors.

    while (i >= 0 && k < 0) {
      if (buffer(i) <= FlatMapK)
        i -= 1
      else
        k = buffer(i)
    }

    conts.unsafeSet(i)
    objectState.unsafeSet(objectState.unsafeIndex() - (orig - i))*/

    /* has to be duplicated from succeeded to ensure call-site monomorphism */
    (ByteStack.pop(conts): @switch) match {
      /* (case 0) will never continue to mapK */
      /* (case 1) will never continue to flatMapK */
      case 0 | 1 =>
        objectState.pop()
        failed(error, depth)

      case 2 => cancelationLoopFailureK(error)
      case 3 => runTerminusFailureK(error)
      case 4 => evalOnFailureK(error)

      case 5 => // handleErrorWithK
        val f = objectState.pop().asInstanceOf[Throwable => IO[Any]]

        try f(error)
        catch {
          case t if NonFatal(t) =>
            failed(t, depth + 1)
          case t: Throwable =>
            onFatalFailure(t)
        }

      case 6 => // onCancelFailureK
        finalizers.pop()
        failed(error, depth + 1)

      case 7 => // uncancelableFailureK
        masks -= 1
        failed(error, depth + 1)

      case 8 => // unmaskFailureK
        masks += 1
        failed(error, depth + 1)

      case 9 => succeeded(Left(error), depth) // attemptK
    }
  }

  private[this] def rescheduleFiber(ec: ExecutionContext, fiber: IOFiber[_]): Unit = {
    if (Platform.isJvm) {
      if (ec.isInstanceOf[WorkStealingThreadPool]) {
        val wstp = ec.asInstanceOf[WorkStealingThreadPool]
        wstp.reschedule(fiber)
      } else {
        scheduleOnForeignEC(ec, fiber)
      }
    } else {
      scheduleOnForeignEC(ec, fiber)
    }
  }

  private[this] def scheduleFiber(ec: ExecutionContext, fiber: IOFiber[_]): Unit = {
    if (Platform.isJvm) {
      if (ec.isInstanceOf[WorkStealingThreadPool]) {
        val wstp = ec.asInstanceOf[WorkStealingThreadPool]
        wstp.execute(fiber)
      } else {
        scheduleOnForeignEC(ec, fiber)
      }
    } else if (Platform.isJs) {
      if (ec.isInstanceOf[BatchingMacrotaskExecutor]) {
        val bmte = ec.asInstanceOf[BatchingMacrotaskExecutor]
        bmte.schedule(fiber)
      } else {
        scheduleOnForeignEC(ec, fiber)
      }
    } else {
      scheduleOnForeignEC(ec, fiber)
    }
  }

  private[this] def scheduleOnForeignEC(ec: ExecutionContext, fiber: IOFiber[_]): Unit = {
    try {
      ec.execute(fiber)
    } catch {
      case _: RejectedExecutionException =>
      /*
       * swallow this exception, since it means we're being externally murdered,
       * so we should just... drop the runloop
       */
    }
  }

  // TODO figure out if the JVM ever optimizes this away
  private[this] def readBarrier(): Unit = {
    suspended.get()
    ()
  }

  /* Implementations of resume methods */

  private[this] def execR(): Unit = {
    // println(s"$name: starting at ${Thread.currentThread().getName} + ${suspended.get()}")
    if (canceled) {
      done(IOFiber.OutcomeCanceled.asInstanceOf[OutcomeIO[A]])
    } else {
      conts = ByteStack.create(16)
      conts = ByteStack.push(conts, RunTerminusK)

      objectState.init(16)
      finalizers.init(16)

      val io = resumeIO
      resumeIO = null
      runLoop(io, runtime.cancelationCheckThreshold, runtime.autoYieldThreshold)
    }
  }

  private[this] def asyncContinueSuccessfulR(): Unit = {
    val a = objectState.pop().asInstanceOf[Any]
    runLoop(succeeded(a, 0), runtime.cancelationCheckThreshold, runtime.autoYieldThreshold)
  }

  private[this] def asyncContinueFailedR(): Unit = {
    val t = objectState.pop().asInstanceOf[Throwable]
    runLoop(failed(t, 0), runtime.cancelationCheckThreshold, runtime.autoYieldThreshold)
  }

  private[this] def asyncContinueCanceledR(): Unit = {
    val fin = prepareFiberForCancelation(null)
    runLoop(fin, runtime.cancelationCheckThreshold, runtime.autoYieldThreshold)
  }

  private[this] def asyncContinueCanceledWithFinalizerR(): Unit = {
    val cb = objectState.pop().asInstanceOf[Either[Throwable, Unit] => Unit]
    val fin = prepareFiberForCancelation(cb)
    runLoop(fin, runtime.cancelationCheckThreshold, runtime.autoYieldThreshold)
  }

  private[this] def blockingR(): Unit = {
    var error: Throwable = null
    val cur = resumeIO.asInstanceOf[Blocking[Any]]
    resumeIO = null
    val r =
      try cur.thunk()
      catch {
        case t if NonFatal(t) =>
          error = t
        case t: Throwable =>
          onFatalFailure(t)
      }

    if (isStackTracing) {
      // Remove the reference to the fiber monitor handle
      objectState.pop().asInstanceOf[WeakBag.Handle].deregister()
    }

    if (error == null) {
      resumeTag = AsyncContinueSuccessfulR
      objectState.push(r.asInstanceOf[AnyRef])
    } else {
      resumeTag = AsyncContinueFailedR
      objectState.push(error)
    }
    val ec = currentCtx
    scheduleOnForeignEC(ec, this)
  }

  private[this] def cedeR(): Unit = {
    runLoop(succeeded((), 0), runtime.cancelationCheckThreshold, runtime.autoYieldThreshold)
  }

  private[this] def autoCedeR(): Unit = {
    val io = resumeIO
    resumeIO = null
    runLoop(io, runtime.cancelationCheckThreshold, runtime.autoYieldThreshold)
  }

  /* Implementations of continuations */

  private[this] def cancelationLoopSuccessK(): IO[Any] = {
    if (!finalizers.isEmpty()) {
      // There are still remaining finalizers to execute. Continue.
      conts = ByteStack.push(conts, CancelationLoopK)
      finalizers.pop()
    } else {
      // The last finalizer is done executing.

      // Unblock the canceler of this fiber.
      val cb = objectState.pop()
      if (cb != null) {
        cb.asInstanceOf[Either[Throwable, Unit] => Unit](RightUnit)
      }

      // Unblock the joiners of this fiber.
      done(IOFiber.OutcomeCanceled.asInstanceOf[OutcomeIO[A]])

      // Exit from the run loop after this. The fiber is finished.
      IO.EndFiber
    }
  }

  private[this] def cancelationLoopFailureK(t: Throwable): IO[Any] = {
    currentCtx.reportFailure(t)
    cancelationLoopSuccessK()
  }

  private[this] def runTerminusSuccessK(result: Any): IO[Any] = {
    done(Outcome.Succeeded(IO.pure(result.asInstanceOf[A])))
    IO.EndFiber
  }

  private[this] def runTerminusFailureK(t: Throwable): IO[Any] = {
    done(Outcome.Errored(t))
    IO.EndFiber
  }

  private[this] def evalOnSuccessK(result: Any): IO[Any] = {
    if (isStackTracing) {
      // Remove the reference to the fiber monitor handle
      objectState.pop().asInstanceOf[WeakBag.Handle].deregister()
    }
    val ec = objectState.pop().asInstanceOf[ExecutionContext]
    currentCtx = ec

    if (!shouldFinalize()) {
      resumeTag = AsyncContinueSuccessfulR
      objectState.push(result.asInstanceOf[AnyRef])
      scheduleOnForeignEC(ec, this)
      IO.EndFiber
    } else {
      prepareFiberForCancelation(null)
    }
  }

  private[this] def evalOnFailureK(t: Throwable): IO[Any] = {
    if (isStackTracing) {
      // Remove the reference to the fiber monitor handle
      objectState.pop().asInstanceOf[WeakBag.Handle].deregister()
    }
    val ec = objectState.pop().asInstanceOf[ExecutionContext]
    currentCtx = ec

    if (!shouldFinalize()) {
      resumeTag = AsyncContinueFailedR
      objectState.push(t)
      scheduleOnForeignEC(ec, this)
      IO.EndFiber
    } else {
      prepareFiberForCancelation(null)
    }
  }

  private[this] def pushTracingEvent(te: TracingEvent): Unit = {
    if (te ne null) {
      tracingEvents.push(te)
    }
  }

  // overrides the AtomicReference#toString
  override def toString: String = {
    val state = if (suspended.get()) "SUSPENDED" else if (isDone) "COMPLETED" else "RUNNING"
    val tracingEvents = this.tracingEvents

    // There are race conditions here since a running fiber is writing to `tracingEvents`,
    // but we don't worry about those since we are just looking for a single `TraceEvent`
    // which references user-land code
    val opAndCallSite =
      Tracing.getFrames(tracingEvents).headOption.map(frame => s": $frame").getOrElse("")

    s"cats.effect.IOFiber@${System.identityHashCode(this).toHexString} $state$opAndCallSite"
  }

  private[effect] def isDone: Boolean =
    outcome ne null

  private[effect] def captureTrace(): Trace =
    if (tracingEvents ne null) {
      suspended.get()
      Trace(tracingEvents)
    } else {
      Trace(RingBuffer.empty(1))
    }
}

private object IOFiber {
  /* prefetch */
  private[IOFiber] val TypeBlocking = Sync.Type.Blocking
  private[IOFiber] val OutcomeCanceled = Outcome.Canceled()
  private[effect] val RightUnit = Right(())

  def onFatalFailure(t: Throwable): Null = {
    val interrupted = Thread.interrupted()

    if (IORuntime.globalFatalFailureHandled.compareAndSet(false, true)) {
      IORuntime.allRuntimes.synchronized {
        var r = 0
        val runtimes = IORuntime.allRuntimes.unsafeHashtable()
        val length = runtimes.length
        while (r < length) {
          val ref = runtimes(r)
          if (ref.isInstanceOf[IORuntime]) {
            val rt = ref.asInstanceOf[IORuntime]

            rt.shutdown()

            // Make sure the shutdown did not interrupt this thread.
            Thread.interrupted()

            var idx = 0
            val tables = rt.fiberErrorCbs.tables
            val numTables = rt.fiberErrorCbs.numTables
            while (idx < numTables) {
              val table = tables(idx)
              table.synchronized {
                val hashtable = table.unsafeHashtable()
                val len = hashtable.length
                var i = 0
                while (i < len) {
                  val ref = hashtable(i)
                  if (ref.isInstanceOf[_ => _]) {
                    val cb = ref.asInstanceOf[Throwable => Unit]
                    cb(t)
                  }
                  i += 1
                }
              }
              idx += 1
            }
          }

          r += 1
        }
      }
    }

    if (interrupted) {
      Thread.currentThread().interrupt()
    }

    throw t
  }
}
