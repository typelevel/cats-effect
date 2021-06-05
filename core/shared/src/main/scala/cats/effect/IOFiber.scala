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

package cats.effect

import cats.effect.unsafe._

import cats.arrow.FunctionK

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
    private[this] val initMask: Int,
    initLocalState: IOLocalState,
    cb: OutcomeIO[A] => Unit,
    startIO: IO[A],
    startEC: ExecutionContext,
    private[this] val runtime: IORuntime
) extends IOFiberPlatform[A]
    with FiberIO[A]
    with Runnable {
  /* true when semantically blocking (ensures that we only unblock *once*) */
  suspended: AtomicBoolean =>

  import IO._
  import IOFiberConstants._

  /*
   * Ideally these would be on the stack, but they can't because we sometimes need to
   * relocate our runloop to another fiber.
   */
  private[this] val conts: ByteStack = new ByteStack()
  private[this] val objectState: ArrayStack[AnyRef] = new ArrayStack()

  /* fast-path to head */
  private[this] var currentCtx: ExecutionContext = startEC
  private[this] val ctxs: ArrayStack[ExecutionContext] = new ArrayStack()

  private[this] var canceled: Boolean = false
  private[this] var masks: Int = initMask
  private[this] var finalizing: Boolean = false

  private[this] val finalizers: ArrayStack[IO[Unit]] = new ArrayStack()

  private[this] val callbacks: CallbackStack[A] = new CallbackStack(cb)

  private[this] var localState: IOLocalState = initLocalState

  @volatile
  private[this] var outcome: OutcomeIO[A] = _

  /* mutable state for resuming the fiber in different states */
  private[this] var resumeTag: Byte = ExecR
  private[this] var resumeIO: IO[Any] = startIO

  /* prefetch for Right(()) */
  private[this] val RightUnit: Either[Throwable, Unit] = IOFiber.RightUnit

  /* similar prefetch for EndFiber */
  private[this] val IOEndFiber: IO.EndFiber.type = IO.EndFiber

  private[this] val cancelationCheckThreshold: Int = runtime.config.cancelationCheckThreshold
  private[this] val autoYieldThreshold: Int = runtime.config.autoYieldThreshold

  override def run(): Unit = {
    // insert a read barrier after every async boundary
    readBarrier()
    (resumeTag: @switch) match {
      case 0 => execR()
      case 1 => asyncContinueSuccessfulR()
      case 2 => asyncContinueFailedR()
      case 3 => blockingR()
      case 4 => afterBlockingSuccessfulR()
      case 5 => afterBlockingFailedR()
      case 6 => evalOnR()
      case 7 => cedeR()
      case 8 => autoCedeR()
      case 9 => ()
    }
  }

  var cancel: IO[Unit] = IO uncancelable { _ =>
    IO defer {
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
            asyncCancel(fin)
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
  }

  /* this is swapped for an `IO.pure(outcome)` when we complete */
  var join: IO[OutcomeIO[A]] =
    IO.async { cb =>
      IO {
        val handle = registerListener(oc => cb(Right(oc)))

        if (handle == null)
          None /* we were already invoked, so no `CallbackStack` needs to be managed */
        else
          Some(IO(handle.clearCurrent()))
      }
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
    if (_cur0 eq IOEndFiber) {
      return
    }

    /* Null IO, blow up but keep the failure within IO */
    val cur0: IO[Any] = if (_cur0 == null) {
      IO.Error(new NullPointerException())
    } else {
      _cur0
    }

    var nextCancelation = cancelationIterations - 1
    var nextAutoCede = autoCedeIterations
    if (cancelationIterations <= 0) {
      // Ensure that we see cancelation.
      readBarrier()
      nextCancelation = cancelationCheckThreshold
      // automatic yielding threshold is always a multiple of the cancelation threshold
      nextAutoCede -= nextCancelation
    }

    if (shouldFinalize()) {
      asyncCancel(null)
    } else if (autoCedeIterations <= 0) {
      resumeIO = cur0
      resumeTag = AutoCedeR
      rescheduleFiber(currentCtx)(this)
    } else {
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

          var error: Throwable = null
          val r =
            try cur.thunk()
            catch {
              case NonFatal(t) =>
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
            succeeded(runtime.scheduler.nowMillis().millis, 0),
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

          val ioe = cur.ioe
          val f = cur.f

          def next(v: Any): IO[Any] = {
            var error: Throwable = null
            val result =
              try f(v)
              catch {
                case NonFatal(t) =>
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

              // this code is inlined in order to avoid two `try` blocks
              var error: Throwable = null
              val result =
                try f(delay.thunk())
                catch {
                  case NonFatal(t) =>
                    error = t
                  case t: Throwable =>
                    onFatalFailure(t)
                }

              val nextIO = if (error == null) succeeded(result, 0) else failed(error, 0)
              runLoop(nextIO, nextCancelation - 1, nextAutoCede)

            case 3 =>
              val realTime = runtime.scheduler.nowMillis().millis
              runLoop(next(realTime), nextCancelation - 1, nextAutoCede)

            case 4 =>
              val monotonic = runtime.scheduler.monotonicNanos().nanos
              runLoop(next(monotonic), nextCancelation - 1, nextAutoCede)

            case 5 =>
              val ec = currentCtx
              runLoop(next(ec), nextCancelation - 1, nextAutoCede)

            case _ =>
              objectState.push(f)
              conts.push(MapK)
              runLoop(ioe, nextCancelation, nextAutoCede)
          }

        case 7 =>
          val cur = cur0.asInstanceOf[FlatMap[Any, Any]]

          val ioe = cur.ioe
          val f = cur.f

          def next(v: Any): IO[Any] =
            try f(v)
            catch {
              case NonFatal(t) =>
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

              // this code is inlined in order to avoid two `try` blocks
              val result =
                try f(delay.thunk())
                catch {
                  case NonFatal(t) =>
                    failed(t, 0)
                  case t: Throwable =>
                    onFatalFailure(t)
                }

              runLoop(result, nextCancelation - 1, nextAutoCede)

            case 3 =>
              val realTime = runtime.scheduler.nowMillis().millis
              runLoop(next(realTime), nextCancelation - 1, nextAutoCede)

            case 4 =>
              val monotonic = runtime.scheduler.monotonicNanos().nanos
              runLoop(next(monotonic), nextCancelation - 1, nextAutoCede)

            case 5 =>
              val ec = currentCtx
              runLoop(next(ec), nextCancelation - 1, nextAutoCede)

            case _ =>
              objectState.push(f)
              conts.push(FlatMapK)
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
              runLoop(succeeded(Left(error.t), 0), nextCancelation - 1, nextAutoCede)

            case 2 =>
              val delay = ioa.asInstanceOf[Delay[Any]]

              // this code is inlined in order to avoid two `try` blocks
              var error: Throwable = null
              val result =
                try delay.thunk()
                catch {
                  case NonFatal(t) =>
                    error = t
                  case t: Throwable =>
                    onFatalFailure(t)
                }

              val next =
                if (error == null) succeeded(Right(result), 0) else succeeded(Left(error), 0)
              runLoop(next, nextCancelation - 1, nextAutoCede)

            case 3 =>
              val realTime = runtime.scheduler.nowMillis().millis
              runLoop(succeeded(Right(realTime), 0), nextCancelation - 1, nextAutoCede)

            case 4 =>
              val monotonic = runtime.scheduler.monotonicNanos().nanos
              runLoop(succeeded(Right(monotonic), 0), nextCancelation - 1, nextAutoCede)

            case 5 =>
              val ec = currentCtx
              runLoop(succeeded(Right(ec), 0), nextCancelation - 1, nextAutoCede)

            case _ =>
              conts.push(AttemptK)
              runLoop(ioa, nextCancelation, nextAutoCede)
          }

        case 9 =>
          val cur = cur0.asInstanceOf[HandleErrorWith[Any]]

          objectState.push(cur.f)
          conts.push(HandleErrorWithK)

          runLoop(cur.ioa, nextCancelation, nextAutoCede)

        /* Canceled */
        case 10 =>
          canceled = true
          if (isUnmasked()) {
            /* run finalizers immediately */
            asyncCancel(null)
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
          conts.push(OnCancelK)
          runLoop(cur.ioa, nextCancelation, nextAutoCede)

        case 12 =>
          val cur = cur0.asInstanceOf[Uncancelable[Any]]

          masks += 1
          val id = masks
          val poll = new Poll[IO] {
            def apply[B](ioa: IO[B]) = IO.Uncancelable.UnmaskRunLoop(ioa, id)
          }

          /*
           * The uncancelableK marker is used by `succeeded` and `failed`
           * to unmask once body completes.
           */
          conts.push(UncancelableK)
          runLoop(cur.body(poll), nextCancelation, nextAutoCede)

        case 13 =>
          val cur = cur0.asInstanceOf[Uncancelable.UnmaskRunLoop[Any]]

          /*
           * we keep track of nested uncancelable sections.
           * The outer block wins.
           */
          if (masks == cur.id) {
            masks -= 1
            /*
             * The UnmaskK marker gets used by `succeeded` and `failed`
             * to restore masking state after `cur.ioa` has finished
             */
            conts.push(UnmaskK)
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

          /*
           *`get` and `cb` (callback) race over the runloop.
           * If `cb` finishes after `get`, `get` just terminates by
           * suspending, and `cb` will resume the runloop via
           * `asyncContinue`.
           *
           * If `get` wins, it gets the result from the `state`
           * `AtomicRef` and it continues, while the callback just
           * terminates (`stateLoop`, when `tag == 3`)
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
                // `wasFinalizing` is published
                if (finalizing == state.wasFinalizing) {
                  if (!shouldFinalize()) {
                    /* we weren't canceled or completed, so schedule the runloop for execution */
                    val ec = currentCtx
                    e match {
                      case Left(t) =>
                        resumeTag = AsyncContinueFailedR
                        objectState.push(t)
                      case Right(a) =>
                        resumeTag = AsyncContinueSuccessfulR
                        objectState.push(a.asInstanceOf[AnyRef])
                    }
                    execute(ec)(this)
                  } else {
                    /*
                     * we were canceled, but since we have won the race on `suspended`
                     * via `resume`, `cancel` cannot run the finalisers, and we have to.
                     */
                    asyncCancel(null)
                  }
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

            /*
             * CAS loop to update the Cont state machine:
             * 0 - Initial
             * 1 - (Get) Waiting
             * 2 - (Cb) Result
             *
             * If state is Initial or Waiting, update the state,
             * and then if `get` has been flatMapped somewhere already
             * and is waiting for a result (i.e. it has suspended),
             * acquire runloop to continue.
             *
             * If not, `cb` arrived first, so it just sets the result and die off.
             *
             * If `state` is `Result`, the callback has been already invoked, so no-op.
             * (guards from double calls)
             */
            @tailrec
            def stateLoop(): Unit = {
              val tag = state.get()
              if (tag <= ContStateWaiting) {
                if (!state.compareAndSet(tag, ContStateWinner)) stateLoop()
                else {
                  state.result = e
                  // The winner has to publish the result.
                  state.set(ContStateResult)
                  if (tag == ContStateWaiting) {
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
           * restore the state to Initial to ensure a subsequent `Get` in
           * a finalizer still works with the same logic.
           */
          val fin = IO {
            state.compareAndSet(ContStateWaiting, ContStateInitial)
            ()
          }
          finalizers.push(fin)
          conts.push(OnCancelK)

          if (state.compareAndSet(ContStateInitial, ContStateWaiting)) {
            /*
             * `state` was Initial, so `get` has arrived before the callback,
             * it needs to set the state to `Waiting` and suspend: `cb` will
             * resume with the result once that's ready
             */

            /*
             * we set the finalizing check to the *suspension* point, which may
             * be in a different finalizer scope than the cont itself.
             * `wasFinalizing` is published by a volatile store on `suspended`.
             */
            state.wasFinalizing = finalizing

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
                if (shouldFinalize())
                  asyncCancel(null)
                else
                  suspend()
              }
            }
          } else {
            /*
             * state was no longer Initial, so the callback has already been invoked
             * and the state is Result.
             * We leave the Result state unmodified so that `get` is idempotent.
             *
             * Note that it's impossible for `state` to be `Waiting` here:
             * - `cont` doesn't allow concurrent calls to `get`, so there can't be
             *    another `get` in `Waiting` when we execute this.
             *
             * - If a previous `get` happened before this code, and we are in a `flatMap`
             *   or `handleErrorWith`, it means the callback has completed once
             *   (unblocking the first `get` and letting us execute), and the state is still
             *   `Result`
             *
             * - If a previous `get` has been canceled and we are being called within an
             *  `onCancel` of that `get`, the finalizer installed on the `Get` node by `Cont`
             *   has restored the state to `Initial` before the execution of this method,
             *   which would have been caught by the previous branch unless the `cb` has
             *   completed and the state is `Result`
             */

            // Wait for the winner to publish the result.
            while (state.get() != ContStateResult) ()

            val result = state.result

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
              asyncCancel(null)
            }
          }

        /* Cede */
        case 16 =>
          resumeTag = CedeR
          rescheduleFiber(currentCtx)(this)

        case 17 =>
          val cur = cur0.asInstanceOf[Start[Any]]

          val childMask = initMask + ChildMaskOffset
          val ec = currentCtx
          val fiber = new IOFiber[Any](
            childMask,
            localState,
            null,
            cur.ioa,
            ec,
            runtime
          )

          // println(s"<$name> spawning <$childName>")

          scheduleFiber(ec)(fiber)

          runLoop(succeeded(fiber, 0), nextCancelation, nextAutoCede)

        case 18 =>
          val cur = cur0.asInstanceOf[RacePair[Any, Any]]

          val next =
            IO.async[Either[(OutcomeIO[Any], FiberIO[Any]), (FiberIO[Any], OutcomeIO[Any])]] {
              cb =>
                IO {
                  val childMask = initMask + ChildMaskOffset
                  val ec = currentCtx
                  val rt = runtime

                  val fiberA = new IOFiber[Any](
                    childMask,
                    localState,
                    null,
                    cur.ioa,
                    ec,
                    rt
                  )

                  val fiberB = new IOFiber[Any](
                    childMask,
                    localState,
                    null,
                    cur.iob,
                    ec,
                    rt
                  )

                  fiberA.registerListener(oc => cb(Right(Left((oc, fiberB)))))
                  fiberB.registerListener(oc => cb(Right(Right((fiberA, oc)))))

                  scheduleFiber(ec)(fiberA)
                  scheduleFiber(ec)(fiberB)

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

          val next = IO.async[Unit] { cb =>
            IO {
              val cancel = runtime.scheduler.sleep(cur.delay, () => cb(RightUnit))
              Some(IO(cancel.run()))
            }
          }

          runLoop(next, nextCancelation, nextAutoCede)

        case 20 =>
          val cur = cur0.asInstanceOf[EvalOn[Any]]

          /* fast-path when it's an identity transformation */
          if (cur.ec eq currentCtx) {
            runLoop(cur.ioa, nextCancelation, nextAutoCede)
          } else {
            val ec = cur.ec
            currentCtx = ec
            ctxs.push(ec)
            conts.push(EvalOnK)

            resumeTag = EvalOnR
            resumeIO = cur.ioa
            execute(ec)(this)
          }

        case 21 =>
          val cur = cur0.asInstanceOf[Blocking[Any]]
          /* we know we're on the JVM here */

          if (cur.hint eq IOFiber.TypeBlocking) {
            resumeTag = BlockingR
            resumeIO = cur
            runtime.blocking.execute(this)
          } else {
            runLoop(interruptibleImpl(cur, runtime.blocking), nextCancelation, nextAutoCede)
          }

        case 22 =>
          val cur = cur0.asInstanceOf[Local[Any]]

          val (nextLocalState, value) = cur.f(localState)
          localState = nextLocalState
          runLoop(succeeded(value, 0), nextCancelation, nextAutoCede)
      }
    }
  }

  /*
   * Only the owner of the run-loop can invoke this.
   * Should be invoked at most once per fiber before termination.
   */
  private[this] def done(oc: OutcomeIO[A]): Unit = {
    // println(s"<$name> invoking done($oc); callback = ${callback.get()}")
    join = IO.pure(oc)
    cancel = IO.unit

    outcome = oc

    try {
      callbacks(oc)
    } finally {
      callbacks.lazySet(null) /* avoid leaks */
    }

    /*
     * need to reset masks to 0 to terminate async callbacks
     * in `cont` busy spinning in `loop` on the `!shouldFinalize` check.
     */
    masks = initMask

    resumeTag = DoneR
    resumeIO = null
    suspended.set(false)

    /* clear out literally everything to avoid any possible memory leaks */

    conts.invalidate()
    objectState.invalidate()
    finalizers.invalidate()
    ctxs.invalidate()
    currentCtx = null
  }

  private[this] def asyncCancel(cb: Either[Throwable, Unit] => Unit): Unit = {
    // System.out.println(s"running cancelation (finalizers.length = ${finalizers.unsafeIndex()})")
    finalizing = true

    if (!finalizers.isEmpty()) {
      conts.init(16)
      conts.push(CancelationLoopK)

      objectState.init(16)
      objectState.push(cb)

      /* suppress all subsequent cancelation on this fiber */
      masks += 1
      // println(s"$name: Running finalizers on ${Thread.currentThread().getName}")
      runLoop(finalizers.pop(), cancelationCheckThreshold, autoYieldThreshold)
    } else {
      if (cb != null)
        cb(RightUnit)

      done(IOFiber.OutcomeCanceled.asInstanceOf[OutcomeIO[A]])
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
    masks == initMask

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

  /* returns the *new* context, not the old */
  private[this] def popContext(): ExecutionContext = {
    ctxs.pop()
    val ec = ctxs.peek()
    currentCtx = ec
    ec
  }

  /* can return null, meaning that no CallbackStack needs to be later invalidated */
  private def registerListener(listener: OutcomeIO[A] => Unit): CallbackStack[A] = {
    if (outcome == null) {
      val back = callbacks.push(listener)

      /* double-check */
      if (outcome != null) {
        back.clearCurrent()
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
    (conts.pop(): @switch) match {
      case 0 => mapK(result, depth)
      case 1 => flatMapK(result, depth)
      case 2 => cancelationLoopSuccessK()
      case 3 => runTerminusSuccessK(result)
      case 4 => evalOnSuccessK(result)
      case 5 =>
        /* handleErrorWithK */
        // this is probably faster than the pre-scan we do in failed, since handlers are rarer than flatMaps
        objectState.pop()
        succeeded(result, depth)
      case 6 => onCancelSuccessK(result, depth)
      case 7 => uncancelableSuccessK(result, depth)
      case 8 => unmaskSuccessK(result, depth)
      case 9 => succeeded(Right(result), depth)
    }

  private[this] def failed(error: Throwable, depth: Int): IO[Any] = {
    // println(s"<$name> failed() with $error")
    val buffer = conts.unsafeBuffer()

    var i = conts.unsafeIndex() - 1
    val orig = i
    var k: Byte = -1

    /*
     * short circuit on error by dropping map and flatMap continuations
     * until we hit a continuation that needs to deal with errors.
     */
    while (i >= 0 && k < 0) {
      if (buffer(i) <= FlatMapK)
        i -= 1
      else
        k = buffer(i)
    }

    conts.unsafeSet(i)
    objectState.unsafeSet(objectState.unsafeIndex() - (orig - i))

    /* has to be duplicated from succeeded to ensure call-site monomorphism */
    (k: @switch) match {
      /* (case 0) will never continue to mapK */
      /* (case 1) will never continue to flatMapK */
      case 2 => cancelationLoopFailureK(error)
      case 3 => runTerminusFailureK(error)
      case 4 => evalOnFailureK(error)
      case 5 => handleErrorWithK(error, depth)
      case 6 => onCancelFailureK(error, depth)
      case 7 => uncancelableFailureK(error, depth)
      case 8 => unmaskFailureK(error, depth)
      case 9 => succeeded(Left(error), depth) // attemptK
    }
  }

  private[this] def execute(ec: ExecutionContext)(fiber: IOFiber[_]): Unit = {
    if (ec.isInstanceOf[WorkStealingThreadPool]) {
      ec.asInstanceOf[WorkStealingThreadPool].executeFiber(fiber)
    } else {
      scheduleOnForeignEC(ec)(fiber)
    }
  }

  private[this] def rescheduleFiber(ec: ExecutionContext)(fiber: IOFiber[_]): Unit = {
    if (ec.isInstanceOf[WorkStealingThreadPool]) {
      ec.asInstanceOf[WorkStealingThreadPool].rescheduleFiber(fiber)
    } else {
      scheduleOnForeignEC(ec)(fiber)
    }
  }

  private[this] def scheduleFiber(ec: ExecutionContext)(fiber: IOFiber[_]): Unit = {
    if (ec.isInstanceOf[WorkStealingThreadPool]) {
      ec.asInstanceOf[WorkStealingThreadPool].scheduleFiber(fiber)
    } else {
      scheduleOnForeignEC(ec)(fiber)
    }
  }

  private[this] def scheduleOnForeignEC(ec: ExecutionContext)(fiber: IOFiber[_]): Unit = {
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

  ///////////////////////////////////////
  // Implementations of resume methods //
  ///////////////////////////////////////

  private[this] def execR(): Unit = {
    // println(s"$name: starting at ${Thread.currentThread().getName} + ${suspended.get()}")

    resumeTag = DoneR
    if (canceled) {
      done(IOFiber.OutcomeCanceled.asInstanceOf[OutcomeIO[A]])
    } else {
      conts.init(16)
      conts.push(RunTerminusK)

      objectState.init(16)
      finalizers.init(16)

      ctxs.init(2)
      ctxs.push(currentCtx)

      val io = resumeIO
      resumeIO = null
      runLoop(io, cancelationCheckThreshold, autoYieldThreshold)
    }
  }

  private[this] def asyncContinueSuccessfulR(): Unit = {
    val a = objectState.pop().asInstanceOf[Any]
    runLoop(succeeded(a, 0), cancelationCheckThreshold, autoYieldThreshold)
  }

  private[this] def asyncContinueFailedR(): Unit = {
    val t = objectState.pop().asInstanceOf[Throwable]
    runLoop(failed(t, 0), cancelationCheckThreshold, autoYieldThreshold)
  }

  private[this] def blockingR(): Unit = {
    var error: Throwable = null
    val cur = resumeIO.asInstanceOf[Blocking[Any]]
    resumeIO = null
    val r =
      try cur.thunk()
      catch {
        case NonFatal(t) =>
          error = t
        case t: Throwable =>
          onFatalFailure(t)
      }

    if (error == null) {
      resumeTag = AfterBlockingSuccessfulR
      objectState.push(r.asInstanceOf[AnyRef])
    } else {
      resumeTag = AfterBlockingFailedR
      objectState.push(error)
    }
    currentCtx.execute(this)
  }

  private[this] def afterBlockingSuccessfulR(): Unit = {
    val result = objectState.pop()
    runLoop(succeeded(result, 0), cancelationCheckThreshold, autoYieldThreshold)
  }

  private[this] def afterBlockingFailedR(): Unit = {
    val error = objectState.pop().asInstanceOf[Throwable]
    runLoop(failed(error, 0), cancelationCheckThreshold, autoYieldThreshold)
  }

  private[this] def evalOnR(): Unit = {
    val ioa = resumeIO
    resumeIO = null
    runLoop(ioa, cancelationCheckThreshold, autoYieldThreshold)
  }

  private[this] def cedeR(): Unit = {
    runLoop(succeeded((), 0), cancelationCheckThreshold, autoYieldThreshold)
  }

  private[this] def autoCedeR(): Unit = {
    val io = resumeIO
    resumeIO = null
    runLoop(io, cancelationCheckThreshold, autoYieldThreshold)
  }

  //////////////////////////////////////
  // Implementations of continuations //
  //////////////////////////////////////

  private[this] def mapK(result: Any, depth: Int): IO[Any] = {
    val f = objectState.pop().asInstanceOf[Any => Any]

    var error: Throwable = null

    val transformed =
      try f(result)
      catch {
        case NonFatal(t) =>
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
  }

  private[this] def flatMapK(result: Any, depth: Int): IO[Any] = {
    val f = objectState.pop().asInstanceOf[Any => IO[Any]]

    try f(result)
    catch {
      case NonFatal(t) =>
        failed(t, depth + 1)
      case t: Throwable =>
        onFatalFailure(t)
    }
  }

  private[this] def cancelationLoopSuccessK(): IO[Any] = {
    if (!finalizers.isEmpty()) {
      conts.push(CancelationLoopK)
      runLoop(finalizers.pop(), cancelationCheckThreshold, autoYieldThreshold)
    } else {
      /* resume external canceller */
      val cb = objectState.pop()
      if (cb != null) {
        cb.asInstanceOf[Either[Throwable, Unit] => Unit](RightUnit)
      }
      /* resume joiners */
      done(IOFiber.OutcomeCanceled.asInstanceOf[OutcomeIO[A]])
    }

    IOEndFiber
  }

  private[this] def cancelationLoopFailureK(t: Throwable): IO[Any] = {
    currentCtx.reportFailure(t)

    cancelationLoopSuccessK()
  }

  private[this] def runTerminusSuccessK(result: Any): IO[Any] = {
    done(Outcome.Succeeded(IO.pure(result.asInstanceOf[A])))
    IOEndFiber
  }

  private[this] def runTerminusFailureK(t: Throwable): IO[Any] = {
    done(Outcome.Errored(t))
    IOEndFiber
  }

  private[this] def evalOnSuccessK(result: Any): IO[Any] = {
    val ec = popContext()

    if (!shouldFinalize()) {
      resumeTag = AfterBlockingSuccessfulR
      objectState.push(result.asInstanceOf[AnyRef])
      execute(ec)(this)
    } else {
      asyncCancel(null)
    }

    IOEndFiber
  }

  private[this] def evalOnFailureK(t: Throwable): IO[Any] = {
    val ec = popContext()

    if (!shouldFinalize()) {
      resumeTag = AfterBlockingFailedR
      objectState.push(t)
      execute(ec)(this)
    } else {
      asyncCancel(null)
    }

    IOEndFiber
  }

  private[this] def handleErrorWithK(t: Throwable, depth: Int): IO[Any] = {
    val f = objectState.pop().asInstanceOf[Throwable => IO[Any]]

    try f(t)
    catch {
      case NonFatal(t) =>
        failed(t, depth + 1)
      case t: Throwable =>
        onFatalFailure(t)
    }
  }

  private[this] def onCancelSuccessK(result: Any, depth: Int): IO[Any] = {
    finalizers.pop()
    succeeded(result, depth + 1)
  }

  private[this] def onCancelFailureK(t: Throwable, depth: Int): IO[Any] = {
    finalizers.pop()
    failed(t, depth + 1)
  }

  private[this] def uncancelableSuccessK(result: Any, depth: Int): IO[Any] = {
    masks -= 1
    // System.out.println(s"unmasking after uncancelable (isUnmasked = ${isUnmasked()})")
    succeeded(result, depth + 1)
  }

  private[this] def uncancelableFailureK(t: Throwable, depth: Int): IO[Any] = {
    masks -= 1
    // System.out.println(s"unmasking after uncancelable (isUnmasked = ${isUnmasked()})")
    failed(t, depth + 1)
  }

  private[this] def unmaskSuccessK(result: Any, depth: Int): IO[Any] = {
    masks += 1
    succeeded(result, depth + 1)
  }

  private[this] def unmaskFailureK(t: Throwable, depth: Int): IO[Any] = {
    masks += 1
    failed(t, depth + 1)
  }

  private[this] def onFatalFailure(t: Throwable): Null = {
    Thread.interrupted()
    currentCtx.reportFailure(t)
    runtime.shutdown()

    var idx = 0
    val tables = runtime.fiberErrorCbs.tables
    val numTables = runtime.fiberErrorCbs.numTables
    while (idx < numTables) {
      val table = tables(idx).unsafeHashtable()
      val len = table.length
      table.synchronized {
        var i = 0
        while (i < len) {
          val cb = table(i)
          if (cb ne null) {
            cb(t)
          }
          i += 1
        }
      }
      idx += 1
    }

    Thread.currentThread().interrupt()
    null
  }
}

private object IOFiber {
  /* prefetch */
  private[IOFiber] val TypeBlocking = Sync.Type.Blocking
  private[IOFiber] val OutcomeCanceled = Outcome.Canceled()
  private[effect] val RightUnit = Right(())
}
