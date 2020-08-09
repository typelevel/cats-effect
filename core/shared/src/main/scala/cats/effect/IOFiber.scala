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
 */
private final class IOFiber[A](
    name: String,
    scheduler: unsafe.Scheduler,
    blockingEc: ExecutionContext,
    initMask: Int,
    cb: OutcomeIO[A] => Unit,
    startIO: IO[A],
    startEC: ExecutionContext)
    extends IOFiberPlatform[A]
    with FiberIO[A]
    with Runnable {

  import IO._
  import IOFiberConstants._

  // I would rather have these on the stack, but we can't because we sometimes need to relocate our runloop to another fiber
  private[this] var conts: ByteStack = _
  private[this] val objectState = new ArrayStack[AnyRef](16)

  // fast-path to head
  private[this] var currentCtx: ExecutionContext = _
  private[this] var ctxs: ArrayStack[ExecutionContext] = _

  private[this] var canceled: Boolean = false
  private[this] var masks: Int = initMask
  private[this] var finalizing: Boolean = false

  // allow for 255 masks before conflicting; 255 chosen because it is a familiar bound, and because it's evenly divides UnsignedInt.MaxValue
  // this scheme gives us 16,843,009 (~2^24) potential derived fibers before masks can conflict
  private[this] val childMask: Int = initMask + 255

  private[this] val finalizers = new ArrayStack[IO[Unit]](16)

  private[this] val callbacks = new CallbackStack[A](cb)

  // true when semantically blocking (ensures that we only unblock *once*)
  private[this] val suspended: AtomicBoolean = new AtomicBoolean(true)

  @volatile
  private[this] var outcome: OutcomeIO[A] = _

  private[this] val childCount = IOFiber.childCount

  // similar prefetch for AsyncState
  private[this] val AsyncStateInitial = AsyncState.Initial
  private[this] val AsyncStateRegisteredNoFinalizer = AsyncState.RegisteredNoFinalizer
  private[this] val AsyncStateRegisteredWithFinalizer = AsyncState.RegisteredWithFinalizer
  private[this] val AsyncStateDone = AsyncState.Done

  private[this] val TypeBlocking = Sync.Type.Blocking

  // mutable state for resuming the fiber in different states
  private[this] var resumeTag: Byte = ExecR
  private[this] var resumeNextIteration: Int = 0
  private[this] var asyncContinueEither: Either[Throwable, Any] = _
  private[this] var blockingCur: Blocking[Any] = _
  private[this] var afterBlockingSuccessfulResult: Any = _
  private[this] var afterBlockingFailedError: Throwable = _
  private[this] var evalOnIOA: IO[Any] = _

  // prefetch for Right(())
  private[this] val RightUnit = IOFiber.RightUnit

  // similar prefetch for Outcome
  private[this] val OutcomeCanceled = IOFiber.OutcomeCanceled.asInstanceOf[OutcomeIO[A]]

  var cancel: IO[Unit] = IO uncancelable { _ =>
    IO defer {
      canceled = true

//      println(s"${name}: attempting cancellation")

      // check to see if the target fiber is suspended
      if (resume()) {
        // ...it was! was it masked?
        if (isUnmasked()) {
          // ...nope! take over the target fiber's runloop and run the finalizers
          // println(s"<$name> running cancelation (finalizers.length = ${finalizers.unsafeIndex()})")

          // if we have async finalizers, runLoop may return early
          IO.async_[Unit] { fin =>
//            println(s"${name}: canceller started at ${Thread.currentThread().getName} + ${suspended.get()}")
            asyncCancel(fin)
          }
        } else {
          // it was masked, so we need to wait for it to finish whatever it was doing and cancel itself
          suspend() // allow someone else to take the runloop
          join.void
        }
      } else {
//        println(s"${name}: had to join")
        // it's already being run somewhere; await the finalizers
        join.void
      }
    }
  }

  // this is swapped for an IO.pure(outcome) when we complete
  var join: IO[OutcomeIO[A]] =
    IO.async { cb =>
      IO {
        val handle = registerListener(oc => cb(Right(oc)))

        if (handle == null)
          None // we were already invoked, so no CallbackStack needs to be managed
        else
          Some(IO(handle.clearCurrent()))
      }
    }

  // can return null, meaning that no CallbackStack needs to be later invalidated
  private def registerListener(listener: OutcomeIO[A] => Unit): CallbackStack[A] = {
    if (outcome == null) {
      val back = callbacks.push(listener)

      // double-check
      if (outcome != null) {
        back.clearCurrent()
        listener(outcome) // the implementation of async saves us from double-calls
        null
      } else {
        back
      }
    } else {
      listener(outcome)
      null
    }
  }

  // Only the owner of the run-loop can invoke this.
  // Should be invoked at most once per fiber before termination.
  private[this] def done(oc: OutcomeIO[A]): Unit = {
//     println(s"<$name> invoking done($oc); callback = ${callback.get()}")
    join = IO.pure(oc)
    cancel = IO.unit

    outcome = oc

    try {
      callbacks(oc)
    } finally {
      callbacks.lazySet(null) // avoid leaks
    }

    // need to reset masks to 0 to terminate async callbacks
    // busy spinning in `loop`.
    masks = initMask
    // full memory barrier to publish masks
    suspended.set(false)

    // clear out literally everything to avoid any possible memory leaks

    // conts may be null if the fiber was cancelled before it was started
    if (conts != null)
      conts.invalidate()

    currentCtx = null
    ctxs = null

    objectState.invalidate()

    finalizers.invalidate()

    asyncContinueEither = null
    blockingCur = null
    afterBlockingSuccessfulResult = null
    afterBlockingFailedError = null
    evalOnIOA = null
  }

  /*
  4 possible cases for callback and cancellation:
  1. Callback completes before cancelation and takes over the runloop
  2. Callback completes after cancelation and takes over runloop
  3. Callback completes after cancelation and can't take over the runloop
  4. Callback completes after cancelation and after the finalizers have run, so it can take the runloop, but shouldn't
   */
  private[this] def asyncContinue(e: Either[Throwable, Any]): Unit = {
    val ec = currentCtx
    resumeTag = AsyncContinueR
    asyncContinueEither = e
    execute(ec)(this)
  }

  private[this] def asyncCancel(cb: Either[Throwable, Unit] => Unit): Unit = {
    //       println(s"<$name> running cancelation (finalizers.length = ${finalizers.unsafeIndex()})")
    finalizing = true

    if (!finalizers.isEmpty()) {
      objectState.push(cb)

      conts = new ByteStack(16)
      conts.push(CancelationLoopK)

      // suppress all subsequent cancelation on this fiber
      masks += 1
      //    println(s"$name: Running finalizers on ${Thread.currentThread().getName}")
      runLoop(finalizers.pop(), 0)
    } else {
      if (cb != null)
        cb(RightUnit)

      done(OutcomeCanceled)
    }
  }

  // masks encoding: initMask => no masks, ++ => push, -- => pop
  @tailrec
  private[this] def runLoop(cur0: IO[Any], iteration: Int): Unit = {
    // cur0 will be null when we're semantically blocked
    if (cur0 == null) {
      return
    }

    val nextIteration = if (iteration > 512) {
      readBarrier()
      0
    } else {
      iteration + 1
    }

    if (shouldFinalize()) {
      asyncCancel(null)
    } else {
      // println(s"<$name> looping on $cur0")
      (cur0.tag: @switch) match {
        case 0 =>
          val cur = cur0.asInstanceOf[Pure[Any]]
          runLoop(succeeded(cur.value, 0), nextIteration)

        case 1 =>
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

        case 2 =>
          val cur = cur0.asInstanceOf[Blocking[Any]]
          // we know we're on the JVM here

          if (cur.hint eq TypeBlocking) {
            resumeTag = BlockingR
            blockingCur = cur
            resumeNextIteration = nextIteration
            blockingEc.execute(this)
          } else {
            runLoop(interruptibleImpl(cur, blockingEc), nextIteration)
          }

        case 3 =>
          val cur = cur0.asInstanceOf[Error]
          runLoop(failed(cur.t, 0), nextIteration)

        case 4 =>
          val cur = cur0.asInstanceOf[Async[Any]]

          /*
           * The purpose of this buffer is to ensure that registration takes
           * primacy over callbacks in the event that registration produces
           * errors in sequence. This implements a "queueing" semantic to
           * the callbacks, implying that the callback is sequenced after
           * registration has fully completed, giving us deterministic
           * serialization.
           */
          val state = new AtomicReference[AsyncState](AsyncStateInitial)

          // Async callbacks may only resume if the finalization state
          // remains the same after we re-acquire the runloop
          val wasFinalizing = finalizing

          objectState.push(state)

          val next = cur.k { e =>
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
            def loop(old: Byte): Unit = {
              if (resume()) {
                state.lazySet(AsyncStateDone) // avoid leaks

                // Race condition check:
                // If finalization occurs and an async finalizer suspends the runloop,
                // a window is created where a normal async resumes the runloop.
                if (finalizing == wasFinalizing) {
                  if (!shouldFinalize()) {
                    if (old == 2) {
                      // AsyncStateRegisteredWithFinalizer
                      // we completed and were not canceled, so we pop the finalizer
                      // note that we're safe to do so since we own the runloop
                      finalizers.pop()
                    }

                    asyncContinue(e)
                  } else {
                    asyncCancel(null)
                  }
                } else {
                  suspend()
                }
              } else if (!shouldFinalize()) {
                loop(old)
              }

              // If we reach this point, it means that somebody else owns the run-loop
              // and will handle cancellation.
            }

            val result = AsyncState.Result(e)

            /*
             * CAS loop to update the async state machine:
             * If state is Initial, RegisteredNoFinalizer, or RegisteredWithFinalizer,
             * update the state, and then if registration has completed, acquire runloop.
             * If state is Result or Done, either the callback was already invoked,
             * or async registration failed.
             */
            @tailrec
            def stateLoop(): Unit = {
              val old = state.get()
              val tag = old.tag
              if (tag <= 2) {
                if (state.compareAndSet(old, result)) {
                  if (tag == 1 || tag == 2) {
                    // registration has completed, so reacquire the runloop
                    loop(tag)
                  }
                } else {
                  stateLoop()
                }
              }
            }

            stateLoop()
          }

          conts.push(AsyncK)
          runLoop(next, nextIteration)

        // ReadEC
        case 5 =>
          runLoop(succeeded(currentCtx, 0), nextIteration)

        case 6 =>
          val cur = cur0.asInstanceOf[EvalOn[Any]]

          // fast-path when it's an identity transformation
          if (cur.ec eq currentCtx) {
            runLoop(cur.ioa, nextIteration)
          } else {
            val ec = cur.ec
            currentCtx = ec
            ctxs.push(ec)
            conts.push(EvalOnK)

            resumeTag = EvalOnR
            evalOnIOA = cur.ioa
            resumeNextIteration = nextIteration
            execute(ec)(this)
          }

        case 7 =>
          val cur = cur0.asInstanceOf[Map[Any, Any]]

          objectState.push(cur.f)
          conts.push(MapK)

          runLoop(cur.ioe, nextIteration)

        case 8 =>
          val cur = cur0.asInstanceOf[FlatMap[Any, Any]]

          objectState.push(cur.f)
          conts.push(FlatMapK)

          runLoop(cur.ioe, nextIteration)

        case 9 =>
          val cur = cur0.asInstanceOf[HandleErrorWith[Any]]

          objectState.push(cur.f)
          conts.push(HandleErrorWithK)

          runLoop(cur.ioa, nextIteration)

        case 10 =>
          val cur = cur0.asInstanceOf[OnCancel[Any]]

          finalizers.push(EvalOn(cur.fin, currentCtx))
          // println(s"pushed onto finalizers: length = ${finalizers.unsafeIndex()}")

          conts.push(OnCancelK)
          runLoop(cur.ioa, nextIteration)

        case 11 =>
          val cur = cur0.asInstanceOf[Uncancelable[Any]]

          masks += 1
          val id = masks
          val poll = new Poll[IO] {
            def apply[B](ioa: IO[B]) = IO.UnmaskRunLoop(ioa, id)
          }

          conts.push(UncancelableK)
          runLoop(cur.body(poll), nextIteration)

        // Canceled
        case 12 =>
          canceled = true
          if (!isUnmasked()) {
            runLoop(succeeded((), 0), nextIteration)
          } else {
            // run finalizers immediately
            asyncCancel(null)
          }

        case 13 =>
          val cur = cur0.asInstanceOf[Start[Any]]

          val childName = s"start-${childCount.getAndIncrement()}"
          val initMask2 = childMask

          val ec = currentCtx
          val fiber =
            new IOFiber[Any](childName, scheduler, blockingEc, initMask2, null, cur.ioa, ec)

          // println(s"<$name> spawning <$childName>")

          execute(ec)(fiber)

          runLoop(succeeded(fiber, 0), nextIteration)

        case 14 =>
          // TODO self-cancelation within a nested poll could result in deadlocks in `both`
          // example: uncancelable(p => F.both(fa >> p(canceled) >> fc, fd)).
          // when we check cancelation in the parent fiber, we are using the masking at the point of racePair, rather than just trusting the masking at the point of the poll
          val cur = cur0.asInstanceOf[RacePair[Any, Any]]

          val next =
            IO.async[Either[(OutcomeIO[Any], FiberIO[Any]), (FiberIO[Any], OutcomeIO[Any])]] {
              cb =>
                IO {
                  val initMask2 = childMask
                  val ec = currentCtx
                  val fiberA = new IOFiber[Any](
                    s"racePair-left-${childCount.getAndIncrement()}",
                    scheduler,
                    blockingEc,
                    initMask2,
                    null,
                    cur.ioa,
                    ec)
                  val fiberB = new IOFiber[Any](
                    s"racePair-right-${childCount.getAndIncrement()}",
                    scheduler,
                    blockingEc,
                    initMask2,
                    null,
                    cur.iob,
                    ec)

                  fiberA.registerListener(oc => cb(Right(Left((oc, fiberB)))))
                  fiberB.registerListener(oc => cb(Right(Right((fiberA, oc)))))

                  execute(ec)(fiberA)
                  execute(ec)(fiberB)

                  Some(fiberA.cancel.both(fiberB.cancel).void)
                }
            }

          runLoop(next, nextIteration)

        case 15 =>
          val cur = cur0.asInstanceOf[Sleep]

          val next = IO.async[Unit] { cb =>
            IO {
              val cancel = scheduler.sleep(cur.delay, () => cb(RightUnit))
              Some(IO(cancel.run()))
            }
          }

          runLoop(next, nextIteration)

        // RealTime
        case 16 =>
          runLoop(succeeded(scheduler.nowMillis().millis, 0), nextIteration)

        // Monotonic
        case 17 =>
          runLoop(succeeded(scheduler.monotonicNanos().nanos, 0), nextIteration)

        // Cede
        case 18 =>
          resumeTag = CedeR
          resumeNextIteration = nextIteration
          currentCtx.execute(this)

        case 19 =>
          val cur = cur0.asInstanceOf[UnmaskRunLoop[Any]]

          if (masks == cur.id) {
            masks -= 1
            conts.push(UnmaskK)
          }

          runLoop(cur.ioa, nextIteration)

        case 20 =>
          val cur = cur0.asInstanceOf[Attempt[A]]

          conts.push(AttemptK)
          runLoop(cur.ioa, nextIteration)
      }
    }
  }

  // We should attempt finalization if all of the following are true:
  // 1) We own the runloop
  // 2) We have been cancelled
  // 3) We are unmasked
  private[this] def shouldFinalize(): Boolean =
    canceled && isUnmasked()

  private[this] def isUnmasked(): Boolean =
    masks == initMask

  private[this] def resume(): Boolean =
    suspended.compareAndSet(true, false)

  private[this] def suspend(): Unit =
    suspended.set(true)

  private[this] def suspendWithFinalizationCheck(): Unit = {
    // full memory barrier
    suspended.compareAndSet(false, true)
    // race condition check: we may have been cancelled before we suspended
    if (shouldFinalize()) {
      // if we can re-acquire the run-loop, we can finalize
      // otherwise somebody else acquired it and will eventually finalize
      if (resume()) {
        asyncCancel(null)
      }
    }
  }

  // returns the *new* context, not the old
  private[this] def popContext(): ExecutionContext = {
    ctxs.pop()
    val ec = ctxs.peek()
    currentCtx = ec
    ec
  }

  @tailrec
  private[this] def succeeded(result: Any, depth: Int): IO[Any] =
    (conts.pop(): @switch) match {
      case 0 => mapK(result, depth)
      case 1 => flatMapK(result, depth)
      case 2 => cancelationLoopSuccessK()
      case 3 => runTerminusSuccessK(result)
      case 4 => asyncSuccessK(result)
      case 5 => evalOnSuccessK(result)
      case 6 =>
        // handleErrorWithK
        // this is probably faster than the pre-scan we do in failed, since handlers are rarer than flatMaps
        objectState.pop()
        succeeded(result, depth)
      case 7 => onCancelSuccessK(result, depth)
      case 8 => uncancelableSuccessK(result, depth)
      case 9 => unmaskSuccessK(result, depth)
      case 10 => succeeded(Right(result), depth + 1)
    }

  private[this] def failed(error: Throwable, depth: Int): IO[Any] = {
    // println(s"<$name> failed() with $error")
    val buffer = conts.unsafeBuffer()

    var i = conts.unsafeIndex() - 1
    val orig = i
    var k: Byte = -1

    while (i >= 0 && k < 0) {
      if (buffer(i) == FlatMapK || buffer(i) == MapK)
        i -= 1
      else
        k = buffer(i)
    }

    conts.unsafeSet(i)
    objectState.unsafeSet(objectState.unsafeIndex() - (orig - i))

    // has to be duplicated from succeeded to ensure call-site monomorphism
    (k: @switch) match {
      // (case 0) will never continue to mapK
      // (case 1) will never continue to flatMapK
      case 2 => cancelationLoopFailureK(error)
      case 3 => runTerminusFailureK(error)
      case 4 => asyncFailureK(error, depth)
      case 5 => evalOnFailureK(error)
      case 6 => handleErrorWithK(error, depth)
      case 7 => onCancelFailureK(error, depth)
      case 8 => uncancelableFailureK(error, depth)
      case 9 => unmaskFailureK(error, depth)
      case 10 => succeeded(Left(error), depth + 1)
    }
  }

  private[this] def execute(ec: ExecutionContext)(action: Runnable): Unit = {
    readBarrier()

    try {
      ec.execute(action)
    } catch {
      case _: RejectedExecutionException =>
        () // swallow this exception, since it means we're being externally murdered, so we should just... drop the runloop
    }
  }

  // TODO figure out if the JVM ever optimizes this away
  private[this] def readBarrier(): Unit = {
    suspended.get()
    ()
  }

  private[effect] def debug(): Unit = {
    println("================")
    println(s"fiber: $name")
    println("================")
    println(s"conts = ${conts.unsafeBuffer().toList.filterNot(_ == 0)}")
    println(s"canceled = $canceled")
    println(s"masks = $masks (out of initMask = $initMask)")
    println(s"suspended = ${suspended.get()}")
    println(s"outcome = ${outcome}")
  }

  ///////////////////////////////////////
  // Implementations of resume methods //
  ///////////////////////////////////////

  /**
   * @note This method should not be used outside of the IO run loop under any circumstance.
   */
  def run(): Unit =
    (resumeTag: @switch) match {
      case 0 => execR()
      case 1 => asyncContinueR()
      case 2 => blockingR()
      case 3 => afterBlockingSuccessfulR()
      case 4 => afterBlockingFailedR()
      case 5 => evalOnR()
      case 6 => cedeR()
    }

  private[this] def execR(): Unit = {
    if (resume()) {
      //      println(s"$name: starting at ${Thread.currentThread().getName} + ${suspended.get()}")

      conts = new ByteStack(16)
      conts.push(RunTerminusK)

      ctxs = new ArrayStack[ExecutionContext](2)
      currentCtx = startEC
      ctxs.push(startEC)

      runLoop(startIO, 0)
    }
  }

  private[this] def asyncContinueR(): Unit = {
    val e = asyncContinueEither
    asyncContinueEither = null
    val next = e match {
      case Left(t) => failed(t, 0)
      case Right(a) => succeeded(a, 0)
    }

    runLoop(next, 0)
  }

  private[this] def blockingR(): Unit = {
    var error: Throwable = null
    val cur = blockingCur
    blockingCur = null
    val r =
      try cur.thunk()
      catch {
        case NonFatal(t) => error = t
      }

    if (error == null) {
      resumeTag = AfterBlockingSuccessfulR
      afterBlockingSuccessfulResult = r
      currentCtx.execute(this)
    } else {
      resumeTag = AfterBlockingFailedR
      afterBlockingFailedError = error
      currentCtx.execute(this)
    }
  }

  private[this] def afterBlockingSuccessfulR(): Unit = {
    val result = afterBlockingSuccessfulResult
    afterBlockingSuccessfulResult = null
    runLoop(succeeded(result, 0), resumeNextIteration)
  }

  private[this] def afterBlockingFailedR(): Unit = {
    val error = afterBlockingFailedError
    afterBlockingFailedError = null
    runLoop(failed(error, 0), resumeNextIteration)
  }

  private[this] def evalOnR(): Unit = {
    val ioa = evalOnIOA
    evalOnIOA = null
    runLoop(ioa, resumeNextIteration)
  }

  private[this] def cedeR(): Unit = {
    runLoop(succeeded((), 0), resumeNextIteration)
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
      runLoop(finalizers.pop(), 0)
    } else {
      // resume external canceller
      val cb = objectState.pop()
      if (cb != null) {
        cb.asInstanceOf[Either[Throwable, Unit] => Unit](RightUnit)
      }
      // resume joiners
      done(OutcomeCanceled)
    }

    null
  }

  private[this] def cancelationLoopFailureK(t: Throwable): IO[Any] = {
    currentCtx.reportFailure(t)

    cancelationLoopSuccessK()
  }

  private[this] def runTerminusSuccessK(result: Any): IO[Any] = {
    val outcome: OutcomeIO[A] =
      if (canceled) // this can happen if we don't check the canceled flag before completion
        OutcomeCanceled
      else
        Outcome.Completed(IO.pure(result.asInstanceOf[A]))

    done(outcome)
    null
  }

  private[this] def runTerminusFailureK(t: Throwable): IO[Any] = {
    val outcome: OutcomeIO[A] =
      if (canceled) // this can happen if we don't check the canceled flag before completion
        OutcomeCanceled
      else
        Outcome.Errored(t)

    done(outcome)
    null
  }

  private[this] def asyncSuccessK(result: Any): IO[Any] = {
    val state = objectState.pop().asInstanceOf[AtomicReference[AsyncState]]

    val hasFinalizer = result.asInstanceOf[Option[IO[Unit]]] match {
      case Some(cancelToken) =>
        if (isUnmasked()) {
          finalizers.push(cancelToken)
          true
        } else {
          // if we are masked, don't bother pushing the finalizer
          false
        }
      case None => false
    }

    val newState =
      if (hasFinalizer) AsyncStateRegisteredWithFinalizer else AsyncStateRegisteredNoFinalizer

    if (!state.compareAndSet(AsyncStateInitial, newState)) {
      // the callback was invoked before registration i.e. state is Result
      val result = state.get().result
      state.lazySet(AsyncStateDone) // avoid leaks

      if (!shouldFinalize()) {
        if (hasFinalizer) {
          finalizers.pop()
        }
        asyncContinue(result)
      } else {
        asyncCancel(null)
      }
    } else {
      // callback has not been invoked yet
      suspendWithFinalizationCheck()
    }

    null
  }

  private[this] def asyncFailureK(t: Throwable, depth: Int): IO[Any] = {
    val state = objectState.pop().asInstanceOf[AtomicReference[AsyncState]]

    val old = state.getAndSet(AsyncStateDone)
    if (!old.isInstanceOf[AsyncState.Result]) {
      // if we get an error before the callback, then propagate
      failed(t, depth + 1)
    } else {
      // we got the error *after* the callback, but we have queueing semantics
      // so drop the results

      if (!shouldFinalize())
        asyncContinue(Left(t))
      else
        asyncCancel(null)

      null
    }
  }

  private[this] def evalOnSuccessK(result: Any): IO[Any] = {
    val ec = popContext()

    if (!shouldFinalize()) {
      resumeTag = AfterBlockingSuccessfulR
      afterBlockingSuccessfulResult = result
      resumeNextIteration = 0
      execute(ec)(this)
    } else {
      asyncCancel(null)
    }

    null
  }

  private[this] def evalOnFailureK(t: Throwable): IO[Any] = {
    val ec = popContext()

    if (!shouldFinalize()) {
      resumeTag = AfterBlockingFailedR
      afterBlockingFailedError = t
      resumeNextIteration = 0
      execute(ec)(this)
    } else {
      asyncCancel(null)
    }

    null
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
    succeeded(result, depth + 1)
  }

  private[this] def uncancelableFailureK(t: Throwable, depth: Int): IO[Any] = {
    masks -= 1
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
}

private object IOFiber {
  private val childCount = new AtomicInteger(0)

  // prefetch
  private val OutcomeCanceled = Outcome.Canceled()
  private[effect] val RightUnit = Right(())
}
