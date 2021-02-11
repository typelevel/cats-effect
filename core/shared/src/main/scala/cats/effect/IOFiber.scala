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

import cats.effect.unsafe.{IORuntime, WorkStealingThreadPool}

import cats.arrow.FunctionK

import scala.annotation.{switch, tailrec}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NoStackTrace

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
    initMask: Int,
    cb: OutcomeIO[A] => Unit,
    startIO: IO[A],
    startEC: ExecutionContext,
    runtime: IORuntime
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
  private[this] var conts: ByteStack = _
  private[this] val objectState = new ArrayStack[AnyRef](16)

  /* fast-path to head */
  private[this] var currentCtx: ExecutionContext = _
  private[this] var ctxs: ArrayStack[ExecutionContext] = _

  private[this] var canceled: Boolean = false
  private[this] var masks: Int = initMask
  private[this] var finalizing: Boolean = false

  /*
   * allow for 255 masks before conflicting; 255 chosen because it is a familiar bound,
   * and because it's evenly divides UnsignedInt.MaxValue.
   * This scheme gives us 16,843,009 (~2^24) potential derived fibers before masks can conflict
   */
  private[this] val childMask: Int = initMask + 255

  private[this] val finalizers = new ArrayStack[IO[Unit]](16)

  private[this] val callbacks = new CallbackStack[A](cb)

  @volatile
  private[this] var outcome: OutcomeIO[A] = _

  private[this] val TypeBlocking = Sync.Type.Blocking

  /* mutable state for resuming the fiber in different states */
  private[this] var resumeTag: Byte = ExecR

  /* prefetch for Right(()) */
  private[this] val RightUnit = IOFiber.RightUnit

  /* similar prefetch for Outcome */
  private[this] val OutcomeCanceled = IOFiber.OutcomeCanceled.asInstanceOf[OutcomeIO[A]]

  /* similar prefetch for EndFiber */
  private[this] val IOEndFiber = IO.EndFiber

  private[this] val cancellationCheckThreshold = runtime.config.cancellationCheckThreshold
  private[this] val autoYieldThreshold = runtime.config.autoYieldThreshold
  private[this] val iterationThreshold = autoYieldThreshold

  override def run(): Unit = {
    // insert a read barrier after every async boundary
    readBarrier()
    try {
      (resumeTag: @switch) match {
        case 0 => execR()
        case 1 => asyncContinueR()
        case 2 => blockingR()
        case 3 => afterBlockingSuccessfulR()
        case 4 => afterBlockingFailedR()
        case 5 => evalOnR()
        case 6 => cedeR()
        case 7 => autoCedeR()
        case 8 => ()
      }
    } catch {
      case t: Throwable =>
        runtime.internalShutdown()
        runtime.shutdown()
        Thread.interrupted()
        currentCtx.reportFailure(t)
        runtime.fiberErrorCbs.synchronized {
          var idx = 0
          val len = runtime.fiberErrorCbs.hashtable.length
          while (idx < len) {
            val cb = runtime.fiberErrorCbs.hashtable(idx)
            if (cb != null) {
              cb(t)
            }
            idx += 1
          }
        }
        Thread.currentThread().interrupt()
    }
  }

  var cancel: IO[Unit] = IO uncancelable { _ =>
    IO defer {
      canceled = true

      // println(s"${name}: attempting cancellation")

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
  private[this] def runLoop(_cur0: IO[Any], iteration: Int): Unit = {
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

    val nextIteration = if (iteration > iterationThreshold) {
      1
    } else {
      iteration + 1
    }

    if ((nextIteration % cancellationCheckThreshold) == 0) {
      //Ensure that we see cancellation
      readBarrier()
    }

    if (shouldFinalize()) {
      asyncCancel(null)
    } else if ((nextIteration % autoYieldThreshold) == 0) {
      objectState.push(cur0)
      autoCede()
    } else {
      // println(s"<$name> looping on $cur0")
      /*
       * The cases have to use continuous constants to generate a `tableswitch`.
       * Do not name or reorder them.
       */
      (cur0.tag: @switch) match {
        case 0 =>
          val cur = cur0.asInstanceOf[Pure[Any]]
          runLoop(succeeded(cur.value, 0), nextIteration)

        case 1 =>
          val cur = cur0.asInstanceOf[Map[Any, Any]]

          objectState.push(cur.f)
          conts.push(MapK)

          runLoop(cur.ioe, nextIteration)

        case 2 =>
          val cur = cur0.asInstanceOf[FlatMap[Any, Any]]

          objectState.push(cur.f)
          conts.push(FlatMapK)

          runLoop(cur.ioe, nextIteration)

        case 3 =>
          val cur = cur0.asInstanceOf[Error]
          runLoop(failed(cur.t, 0), nextIteration)

        case 4 =>
          val cur = cur0.asInstanceOf[Attempt[Any]]

          conts.push(AttemptK)
          runLoop(cur.ioa, nextIteration)

        case 5 =>
          val cur = cur0.asInstanceOf[HandleErrorWith[Any]]

          objectState.push(cur.f)
          conts.push(HandleErrorWithK)

          runLoop(cur.ioa, nextIteration)

        case 6 =>
          val cur = cur0.asInstanceOf[Delay[Any]]

          var error: Throwable = null
          val r =
            try cur.thunk()
            catch {
              case NonFatal(t) => error = t
            }

          val next =
            if (error == null) succeeded(r, 0)
            else failed(error, 0)

          runLoop(next, nextIteration)

        /* Canceled */
        case 7 =>
          canceled = true
          if (isUnmasked()) {
            /* run finalizers immediately */
            asyncCancel(null)
          } else {
            runLoop(succeeded((), 0), nextIteration)
          }

        case 8 =>
          val cur = cur0.asInstanceOf[OnCancel[Any]]

          finalizers.push(EvalOn(cur.fin, currentCtx))
          // println(s"pushed onto finalizers: length = ${finalizers.unsafeIndex()}")

          /*
           * the OnCancelK marker is used by `succeeded` to remove the
           * finalizer when `ioa` completes uninterrupted.
           */
          conts.push(OnCancelK)
          runLoop(cur.ioa, nextIteration)

        case 9 =>
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
          runLoop(cur.body(poll), nextIteration)

        case 10 =>
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

          runLoop(cur.ioa, nextIteration)

        case 11 =>
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
                    /* we weren't cancelled, so resume the runloop */
                    asyncContinue(e)
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
              } else if (!shouldFinalize()) {
                /*
                 * If we aren't canceled, loop on `suspended` to wait
                 * until `get` has released ownership of the runloop.
                 */
                loop()
              } /*
               * If we are canceled, just die off and let `cancel` or `get` win
               * the race to `resume` and run the finalisers.
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
                if (!state.compareAndSet(tag, ContStateResult)) stateLoop()
                else {
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

            // The result will be published when the CAS on `state` succeeds.
            state.result = e
            stateLoop()
          }

          val get: IO[Any] = IOCont.Get(state)

          val next = body[IO].apply(cb, get, FunctionK.id)

          runLoop(next, nextIteration)

        case 12 =>
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
          finalizers.push(EvalOn(fin, currentCtx))
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
             * race condition check: we may have been cancelled
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
                asyncCancel(null)
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
            val result = state.result

            if (!shouldFinalize()) {
              /* we weren't cancelled, so resume the runloop */
              asyncContinue(result)
            } else {
              /*
               * we were canceled, but `cancel` cannot run the finalisers
               * because the runloop was not suspended, so we have to run them
               */
              asyncCancel(null)
            }
          }

        /* Cede */
        case 13 =>
          cede()

        case 14 =>
          val cur = cur0.asInstanceOf[Start[Any]]

          val initMask2 = childMask
          val ec = currentCtx
          val fiber = new IOFiber[Any](
            initMask2,
            null,
            cur.ioa,
            ec,
            runtime
          )

          // println(s"<$name> spawning <$childName>")

          rescheduleAndNotify(ec)(fiber)

          runLoop(succeeded(fiber, 0), nextIteration)

        case 15 =>
          val cur = cur0.asInstanceOf[Sleep]

          val next = IO.async[Unit] { cb =>
            IO {
              val cancel = runtime.scheduler.sleep(cur.delay, () => cb(RightUnit))
              Some(IO(cancel.run()))
            }
          }

          runLoop(next, nextIteration)

        /* RealTime */
        case 16 =>
          runLoop(succeeded(runtime.scheduler.nowMillis().millis, 0), nextIteration)

        /* Monotonic */
        case 17 =>
          runLoop(succeeded(runtime.scheduler.monotonicNanos().nanos, 0), nextIteration)

        /* ReadEC */
        case 18 =>
          runLoop(succeeded(currentCtx, 0), nextIteration)

        case 19 =>
          val cur = cur0.asInstanceOf[EvalOn[Any]]

          /* fast-path when it's an identity transformation */
          if (cur.ec eq currentCtx) {
            runLoop(cur.ioa, nextIteration)
          } else {
            val ec = cur.ec
            currentCtx = ec
            ctxs.push(ec)
            conts.push(EvalOnK)

            resumeTag = EvalOnR
            objectState.push(cur.ioa)
            execute(ec)(this)
          }

        case 20 =>
          val cur = cur0.asInstanceOf[Blocking[Any]]
          /* we know we're on the JVM here */

          if (cur.hint eq TypeBlocking) {
            resumeTag = BlockingR
            objectState.push(cur)
            runtime.blocking.execute(this)
          } else {
            runLoop(interruptibleImpl(cur, runtime.blocking), nextIteration)
          }
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
    /*
     * Write barrier to publish masks. The thread which owns the runloop is
     * effectively a single writer, so lazy set can be utilized for relaxed
     * memory barriers (equivalent to a `release` set), while still keeping
     * the same memory publishing semantics as `set(false)`.
     *
     * http://psy-lob-saw.blogspot.com/2012/12/atomiclazyset-is-performance-win-for.html
     */
    suspended.lazySet(false)

    /* clear out literally everything to avoid any possible memory leaks */

    /* conts may be null if the fiber was cancelled before it was started */
    if (conts != null)
      conts.invalidate()

    currentCtx = null
    ctxs = null

    objectState.invalidate()

    finalizers.invalidate()
  }

  private[this] def asyncContinue(e: Either[Throwable, Any]): Unit = {
    val ec = currentCtx
    resumeTag = AsyncContinueR
    objectState.push(e)
    execute(ec)(this)
  }

  private[this] def asyncCancel(cb: Either[Throwable, Unit] => Unit): Unit = {
    // println(s"<$name> running cancelation (finalizers.length = ${finalizers.unsafeIndex()})")
    finalizing = true

    if (!finalizers.isEmpty()) {
      objectState.push(cb)

      conts = new ByteStack(16)
      conts.push(CancelationLoopK)

      /* suppress all subsequent cancelation on this fiber */
      masks += 1
      // println(s"$name: Running finalizers on ${Thread.currentThread().getName}")
      runLoop(finalizers.pop(), 1)
    } else {
      if (cb != null)
        cb(RightUnit)

      done(OutcomeCanceled)
    }
  }

  private[this] def cede(): Unit = {
    resumeTag = CedeR
    reschedule(currentCtx)(this)
  }

  private[this] def autoCede(): Unit = {
    resumeTag = AutoCedeR
    reschedule(currentCtx)(this)
  }

  /*
   * We should attempt finalization if all of the following are true:
   * 1) We own the runloop
   * 2) We have been cancelled
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

  /*
   * The thread which owns the runloop is effectively a single writer, so lazy set
   * can be utilized for relaxed memory barriers (equivalent to a `release` set),
   * while still keeping the same memory publishing semantics as `set(true)`.
   *
   * http://psy-lob-saw.blogspot.com/2012/12/atomiclazyset-is-performance-win-for.html
   */
  private[this] def suspend(): Unit =
    suspended.lazySet(true)

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
     * short circuit on error by dropping map, flatMap, and auto-cede continuations
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
    conts.reclaim()
    objectState.reclaim()
    ctxs.reclaim()
    finalizers.reclaim()
    if (ec.isInstanceOf[WorkStealingThreadPool]) {
      ec.asInstanceOf[WorkStealingThreadPool].executeFiber(fiber)
    } else {
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
  }

  private[this] def reschedule(ec: ExecutionContext)(fiber: IOFiber[_]): Unit = {
    conts.reclaim()
    objectState.reclaim()
    ctxs.reclaim()
    finalizers.reclaim()
    if (ec.isInstanceOf[WorkStealingThreadPool])
      ec.asInstanceOf[WorkStealingThreadPool].rescheduleFiber(fiber)
    else
      scheduleOnForeignEC(ec)(fiber)
  }

  private[this] def rescheduleAndNotify(ec: ExecutionContext)(fiber: IOFiber[_]): Unit = {
    conts.reclaim()
    objectState.reclaim()
    ctxs.reclaim()
    finalizers.reclaim()
    if (ec.isInstanceOf[WorkStealingThreadPool])
      ec.asInstanceOf[WorkStealingThreadPool].rescheduleFiberAndNotify(fiber)
    else
      scheduleOnForeignEC(ec)(fiber)
  }

  private[this] def scheduleOnForeignEC(ec: ExecutionContext)(fiber: IOFiber[_]): Unit =
    try {
      ec.execute(fiber)
    } catch {
      case _: RejectedExecutionException =>
      /*
       * swallow this exception, since it means we're being externally murdered,
       * so we should just... drop the runloop
       */
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
    if (resume()) {
      // println(s"$name: starting at ${Thread.currentThread().getName} + ${suspended.get()}")

      conts = new ByteStack(16)
      conts.push(RunTerminusK)

      ctxs = new ArrayStack[ExecutionContext](2)
      currentCtx = startEC
      ctxs.push(startEC)

      runLoop(startIO, 1)
    }
  }

  private[this] def asyncContinueR(): Unit = {
    val e = objectState.pop().asInstanceOf[Either[Throwable, Any]]
    val next = e match {
      case Left(t) => failed(t, 0)
      case Right(a) => succeeded(a, 0)
    }

    runLoop(next, 1)
  }

  private[this] def blockingR(): Unit = {
    var error: Throwable = null
    val cur = objectState.pop().asInstanceOf[Blocking[Any]]
    val r =
      try cur.thunk()
      catch {
        case NonFatal(t) => error = t
      }

    if (error == null) {
      resumeTag = AfterBlockingSuccessfulR
      objectState.push(r.asInstanceOf[Object])
    } else {
      resumeTag = AfterBlockingFailedR
      objectState.push(error)
    }
    currentCtx.execute(this)
  }

  private[this] def afterBlockingSuccessfulR(): Unit = {
    val result = objectState.pop()
    runLoop(succeeded(result, 0), 1)
  }

  private[this] def afterBlockingFailedR(): Unit = {
    val error = objectState.pop().asInstanceOf[Throwable]
    runLoop(failed(error, 0), 1)
  }

  private[this] def evalOnR(): Unit = {
    val ioa = objectState.pop().asInstanceOf[IO[Any]]
    runLoop(ioa, 0)
  }

  private[this] def cedeR(): Unit = {
    runLoop(succeeded((), 0), 1)
  }

  private[this] def autoCedeR(): Unit = {
    val io = objectState.pop().asInstanceOf[IO[Any]]
    runLoop(io, 1)
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
        case NonFatal(t) => error = t
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
      case NonFatal(t) => failed(t, depth + 1)
    }
  }

  private[this] def cancelationLoopSuccessK(): IO[Any] = {
    if (!finalizers.isEmpty()) {
      conts.push(CancelationLoopK)
      runLoop(finalizers.pop(), 1)
    } else {
      /* resume external canceller */
      val cb = objectState.pop()
      if (cb != null) {
        cb.asInstanceOf[Either[Throwable, Unit] => Unit](RightUnit)
      }
      /* resume joiners */
      done(OutcomeCanceled)
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
      objectState.push(result.asInstanceOf[Object])
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
      case NonFatal(t) => failed(t, depth + 1)
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

    if (shouldFinalize()) {
      asyncCancel(null)
      IOEndFiber
    } else {
      succeeded(result, depth + 1)
    }
  }

  private[this] def uncancelableFailureK(t: Throwable, depth: Int): IO[Any] = {
    masks -= 1

    if (shouldFinalize()) {
      asyncCancel(null)
      IOEndFiber
    } else {
      failed(t, depth + 1)
    }
  }

  private[this] def unmaskSuccessK(result: Any, depth: Int): IO[Any] = {
    masks += 1
    succeeded(result, depth + 1)
  }

  private[this] def unmaskFailureK(t: Throwable, depth: Int): IO[Any] = {
    masks += 1
    failed(t, depth + 1)
  }
}

private object IOFiber {
  /* prefetch */
  private val OutcomeCanceled = Outcome.Canceled()
  private[effect] val RightUnit = Right(())
}

private[effect] case object AsyncPropagateCancelation extends NoStackTrace
