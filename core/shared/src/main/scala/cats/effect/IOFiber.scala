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
 */
private[effect] final class IOFiber[A](
    name: String,
    scheduler: unsafe.Scheduler,
    blockingEc: ExecutionContext,
    initMask: Int)
    extends FiberIO[A] {
  import IO._

  // I would rather have these on the stack, but we can't because we sometimes need to relocate our runloop to another fiber
  private[this] var conts: ByteStack = _

  // fast-path to head
  private[this] var currentCtx: ExecutionContext = _
  private[this] var ctxs: ArrayStack[ExecutionContext] = _

  // TODO a non-volatile cancel bit is very unlikely to be observed, in practice, until we hit an async boundary
  private[this] var canceled: Boolean = false

  private[this] var masks: Int = initMask
  private[this] val finalizers =
    new ArrayStack[IO[Unit]](
      16
    ) // TODO reason about whether or not the final finalizers are visible here

  private[this] val callbacks = new CallbackStack[A](null)

  // true when semantically blocking (ensures that we only unblock *once*)
  private[this] val suspended: AtomicBoolean = new AtomicBoolean(true)

  // TODO we may be able to weaken this to just a @volatile
  private[this] val outcome: AtomicReference[OutcomeIO[A]] =
    new AtomicReference()

  private[this] val objectState = new ArrayStack[AnyRef](16)

  private[this] val childCount = IOFiber.childCount

  // pre-fetching of all continuations (to avoid memory barriers)
  private[this] val CancelationLoopK = IOFiber.CancelationLoopK
  private[this] val RunTerminusK = IOFiber.RunTerminusK
  private[this] val AsyncK = IOFiber.AsyncK
  private[this] val EvalOnK = IOFiber.EvalOnK
  private[this] val MapK = IOFiber.MapK
  private[this] val FlatMapK = IOFiber.FlatMapK
  private[this] val HandleErrorWithK = IOFiber.HandleErrorWithK
  private[this] val OnCancelK = IOFiber.OnCancelK
  private[this] val UncancelableK = IOFiber.UncancelableK
  private[this] val UnmaskK = IOFiber.UnmaskK

  // similar prefetch for Outcome
  private[this] val _OutcomeCanceled = IOFiber.OutcomeCanceled

  // similar prefetch for AsyncState
  private[this] val AsyncStateInitial = AsyncState.Initial
  // private[this] val AsyncStateRegisteredNoFinalizer = AsyncState.RegisteredNoFinalizer
  private[this] val AsyncStateRegisteredWithFinalizer = AsyncState.RegisteredWithFinalizer

  def this(
      scheduler: unsafe.Scheduler,
      blockingEc: ExecutionContext,
      cb: OutcomeIO[A] => Unit,
      initMask: Int) = {
    this("main", scheduler, blockingEc, initMask)
    callbacks.push(cb)
  }

  var cancel: IO[Unit] = IO uncancelable { _ =>
    IO suspend {
      canceled = true
      cancel = IO.unit

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

  // this is swapped for an IO.pure(outcome.get()) when we complete
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
    if (outcome.get() == null) {
      val back = callbacks.push(listener)

      // double-check
      if (outcome.get() != null) {
        back.clearCurrent()
        listener(outcome.get()) // the implementation of async saves us from double-calls
        null
      } else {
        back
      }
    } else {
      listener(outcome.get())
      null
    }
  }

  private[effect] def run(cur: IO[Any], ec: ExecutionContext): Unit = {
    conts = new ByteStack(16)
    pushCont(RunTerminusK)

    ctxs = new ArrayStack[ExecutionContext](2)
    currentCtx = ec
    ctxs.push(ec)

    if (resume()) {
//      println(s"$name: starting at ${Thread.currentThread().getName} + ${suspended.get()}")
      runLoop(cur, 0)
    }
  }

  // Only the owner of the run-loop can invoke this.
  // Should be invoked at most once per fiber before termination.
  private def done(oc: OutcomeIO[A]): Unit = {
//     println(s"<$name> invoking done($oc); callback = ${callback.get()}")
    join = IO.pure(oc)

    outcome.set(oc)

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
  }

  /*
  4 possible cases for callback and cancellation:
  1. Callback completes before cancelation and takes over the runloop
  2. Callback completes after cancelation and takes over runloop
  3. Callback completes after cancelation and can't take over the runloop
  4. Callback completes after cancelation and after the finalizers have run, so it can take the runloop, but shouldn't
   */
  private def asyncContinue(
      state: AtomicReference[AsyncState],
      e: Either[Throwable, Any]): Unit = {
    state.lazySet(AsyncStateInitial) // avoid leaks

    val ec = currentCtx

    if (!isCanceled() || !isUnmasked()) { // hard cancelation check, basically
      execute(ec) { () =>
        val next = e match {
          case Left(t) => failed(t, 0)
          case Right(a) => succeeded(a, 0)
        }

        runLoop(next, 0) // we've definitely hit suspended as part of evaluating async
      }
    } else {
      asyncCancel(null)
    }
  }

  private def asyncCancel(cb: Either[Throwable, Unit] => Unit): Unit = {
    //       println(s"<$name> running cancelation (finalizers.length = ${finalizers.unsafeIndex()})")
    if (hasFinalizers()) {
      objectState.push(cb)

      conts = new ByteStack(16)
      pushCont(CancelationLoopK)

      // suppress all subsequent cancelation on this fiber
      masks += 1
      //    println(s"$name: Running finalizers on ${Thread.currentThread().getName}")
      runLoop(finalizers.pop(), 0)
    } else {
      if (cb != null)
        cb(Right(()))

      done(_OutcomeCanceled.asInstanceOf[OutcomeIO[A]])
    }
  }

  // masks encoding: initMask => no masks, ++ => push, -- => pop
  private def runLoop(cur0: IO[Any], iteration: Int): Unit = {
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

    if (isCanceled() && isUnmasked()) {
      asyncCancel(null)
    } else {
      // println(s"<$name> looping on $cur0")
      if (!conts.isEmpty()) {
        (cur0.tag: @switch) match {
          case 0 =>
            val cur = cur0.asInstanceOf[Pure[Any]]
            runLoop(succeeded(cur.value, 0), nextIteration)

          case 1 =>
            val cur = cur0.asInstanceOf[Delay[Any]]

            var success = false
            val r =
              try {
                val r = cur.thunk()
                success = true
                r
              } catch {
                case NonFatal(t) => t
              }

            val next =
              if (success)
                succeeded(r, 0)
              else
                failed(r, 0)

            runLoop(next, nextIteration)

          case 2 =>
            val cur = cur0.asInstanceOf[Blocking[Any]]

            blockingEc execute { () =>
              var success0 = false
              val r =
                try {
                  val r = cur.thunk()
                  success0 = true
                  r
                } catch {
                  case NonFatal(t) => t
                }
              val success = success0

              currentCtx execute { () =>
                val next =
                  if (success)
                    succeeded(r, 0)
                  else
                    failed(r, 0)

                runLoop(next, nextIteration)
              }
            }

          case 3 =>
            val cur = cur0.asInstanceOf[Error]
            runLoop(failed(cur.t, 0), nextIteration)

          case 4 =>
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
                  if (resume()) {
                    if (old == AsyncStateRegisteredWithFinalizer) {
                      // we completed and were not canceled, so we pop the finalizer
                      // note that we're safe to do so since we own the runloop
                      finalizers.pop()
                    }

                    asyncContinue(state, e)
                  } else if (!isCanceled() || !isUnmasked()) {
                    loop()
                  }

                  // If we reach this point, it means that somebody else owns the run-loop
                  // and will handle cancellation.
                }

                if (old != AsyncStateInitial) {
                  // registration already completed, we're good to go
                  loop()
                }
              }
            }

            pushCont(AsyncK)
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
              pushCont(EvalOnK)

              execute(ec) { () => runLoop(cur.ioa, nextIteration) }
            }

          case 7 =>
            val cur = cur0.asInstanceOf[Map[Any, Any]]

            objectState.push(cur.f)
            pushCont(MapK)

            runLoop(cur.ioe, nextIteration)

          case 8 =>
            val cur = cur0.asInstanceOf[FlatMap[Any, Any]]

            objectState.push(cur.f)
            pushCont(FlatMapK)

            runLoop(cur.ioe, nextIteration)

          case 9 =>
            val cur = cur0.asInstanceOf[HandleErrorWith[Any]]

            objectState.push(cur.f)
            pushCont(HandleErrorWithK)

            runLoop(cur.ioa, nextIteration)

          case 10 =>
            val cur = cur0.asInstanceOf[OnCancel[Any]]

            finalizers.push(EvalOn(cur.fin, currentCtx))
            // println(s"pushed onto finalizers: length = ${finalizers.unsafeIndex()}")

            pushCont(OnCancelK)
            runLoop(cur.ioa, nextIteration)

          case 11 =>
            val cur = cur0.asInstanceOf[Uncancelable[Any]]

            masks += 1
            val id = masks
            val poll = new (IO ~> IO) {
              def apply[B](ioa: IO[B]) = IO.Unmask(ioa, id)
            }

            pushCont(UncancelableK)
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

            val fiber = new IOFiber(childName, scheduler, blockingEc, initMask2)

            // println(s"<$name> spawning <$childName>")

            val ec = currentCtx
            execute(ec)(() => fiber.run(cur.ioa, ec))

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
                    val fiberA = new IOFiber[Any](
                      s"racePair-left-${childCount.getAndIncrement()}",
                      scheduler,
                      blockingEc,
                      initMask2)
                    val fiberB = new IOFiber[Any](
                      s"racePair-right-${childCount.getAndIncrement()}",
                      scheduler,
                      blockingEc,
                      initMask2)

                    fiberA.registerListener(oc => cb(Right(Left((oc, fiberB)))))
                    fiberB.registerListener(oc => cb(Right(Right((fiberA, oc)))))

                    val ec = currentCtx

                    execute(ec)(() => fiberA.run(cur.ioa, ec))
                    execute(ec)(() => fiberB.run(cur.iob, ec))

                    Some(fiberA.cancel.both(fiberB.cancel).void)
                  }
              }

            runLoop(next, nextIteration)

          case 15 =>
            val cur = cur0.asInstanceOf[Sleep]

            val next = IO.async[Unit] { cb =>
              IO {
                val cancel = scheduler.sleep(cur.delay, () => cb(Right(())))
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
            currentCtx execute { () =>
              // println("continuing from cede ")

              runLoop(succeeded((), 0), nextIteration)
            }

          case 19 =>
            val cur = cur0.asInstanceOf[Unmask[Any]]

            if (masks == cur.id) {
              masks -= 1
              pushCont(UnmaskK)
            }

            runLoop(cur.ioa, nextIteration)
        }
      }
    }
  }

  // allow for 255 masks before conflicting; 255 chosen because it is a familiar bound, and because it's evenly divides UnsignedInt.MaxValue
  // this scheme gives us 16,843,009 (~2^24) potential derived fibers before masks can conflict
  private def childMask: Int =
    initMask + 255

  // we use these forwarders because direct field access (private[this]) is faster
  private def isCanceled(): Boolean =
    canceled

  private def hasFinalizers(): Boolean =
    !finalizers.isEmpty()

  private def pushFinalizer(f: IO[Unit]): Unit =
    finalizers.push(f)

  private def popFinalizer(): IO[Unit] =
    finalizers.pop()

  private def pushCont(cont: IOCont): Unit =
    conts.push(cont.tag)

  private def popObjectState(): AnyRef =
    objectState.pop()

  private def isUnmasked(): Boolean =
    masks == initMask

  private def pushMask(): Unit =
    masks += 1

  private def popMask(): Unit =
    masks -= 1

  private[this] def resume(): Boolean = suspended.compareAndSet(true, false)

  private def suspend(): Unit =
    suspended.set(true)

  private def suspendAndCheckCancellation(): Unit = {
    // full memory barrier
    suspended.compareAndSet(false, true)
    // race condition check: we may have been cancelled before we suspended
    if (isCanceled() && isUnmasked()) {
      // if we can acquire the run-loop, we can run the finalizers
      // otherwise somebody else picked it up and will run finalizers
      if (resume()) {
        asyncCancel(null)
      }
    }
  }

  // returns the *new* context, not the old
  private def popContext(): ExecutionContext = {
    ctxs.pop()
    val ec = ctxs.peek()
    currentCtx = ec
    ec
  }

  private def succeeded(result: Any, depth: Int): IO[Any] =
    (conts.pop(): @switch) match {
      case 0 => CancelationLoopK(this, true, result, depth)
      case 1 => RunTerminusK(this, true, result, depth)
      case 2 => AsyncK(this, true, result, depth)
      case 3 => EvalOnK(this, true, result, depth)
      case 4 => MapK(this, true, result, depth)
      case 5 => FlatMapK(this, true, result, depth)
      case 6 => HandleErrorWithK(this, true, result, depth)
      case 7 => OnCancelK(this, true, result, depth)
      case 8 => UncancelableK(this, true, result, depth)
      case 9 => UnmaskK(this, true, result, depth)
    }

  private def failed(error: Any, depth: Int): IO[Any] = {
    // println(s"<$name> failed() with $error")
    val buffer = conts.unsafeBuffer()

    var i = conts.unsafeIndex() - 1
    val orig = i
    var k: Byte = -1

    while (i >= 0 && k < 0) {
      if (buffer(i) == FlatMapK.tag || buffer(i) == MapK.tag)
        i -= 1
      else
        k = buffer(i)
    }

    conts.unsafeSet(i)
    objectState.unsafeSet(objectState.unsafeIndex() - (orig - i))

    // has to be duplicated from succeeded to ensure call-site monomorphism
    (k: @switch) match {
      case 0 => CancelationLoopK(this, false, error, depth)
      case 1 => RunTerminusK(this, false, error, depth)
      case 2 => AsyncK(this, false, error, depth)
      case 3 => EvalOnK(this, false, error, depth)
      case 4 => MapK(this, false, error, depth)
      case 5 => FlatMapK(this, false, error, depth)
      case 6 => HandleErrorWithK(this, false, error, depth)
      case 7 => OnCancelK(this, false, error, depth)
      case 8 => UncancelableK(this, false, error, depth)
      case 9 => UnmaskK(this, false, error, depth)
    }
  }

  private def execute(ec: ExecutionContext)(action: Runnable): Unit = {
    readBarrier()

    try {
      ec.execute(action)
    } catch {
      case _: RejectedExecutionException =>
        () // swallow this exception, since it means we're being externally murdered, so we should just... drop the runloop
    }
  }

  // TODO figure out if the JVM ever optimizes this away
  private def readBarrier(): Unit = {
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
    println(s"outcome = ${outcome.get()}")
  }
}

private object IOFiber {

  private val MaxStackDepth = 512

  private val childCount = new AtomicInteger(0)

  // prefetch
  final private val OutcomeCanceled = Outcome.Canceled()
  final private val OutcomeErrored = Outcome.Errored
  final private val OutcomeCompleted = Outcome.Completed

  // similar prefetch for AsyncState
  private[this] val AsyncStateInitial = AsyncState.Initial
  private[this] val AsyncStateRegisteredNoFinalizer = AsyncState.RegisteredNoFinalizer
  private[this] val AsyncStateRegisteredWithFinalizer = AsyncState.RegisteredWithFinalizer

  ////////////////////////////////////////////////
  // preallocation of all necessary continuations
  ////////////////////////////////////////////////

  private object CancelationLoopK extends IOCont(0) {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

//      println(s"${named}: cancel loop in ${Thread.currentThread().getName}")

      if (hasFinalizers()) {
        pushCont(this)
        runLoop(popFinalizer(), 0)
      } else {
        // resume external canceller
        val cb = popObjectState()
        if (cb != null) {
          cb.asInstanceOf[Either[Throwable, Unit] => Unit](Right(()))
        }
        // resume joiners
        done(OutcomeCanceled.asInstanceOf[OutcomeIO[A]])
      }

      null
    }
  }

  private object RunTerminusK extends IOCont(1) {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self.{done, isCanceled}

      val outcome: OutcomeIO[A] =
        if (isCanceled()) // this can happen if we don't check the canceled flag before completion
          OutcomeCanceled.asInstanceOf[OutcomeIO[A]]
        else if (success)
          OutcomeCompleted(IO.pure(result.asInstanceOf[A]))
        else
          OutcomeErrored(result.asInstanceOf[Throwable])

      done(outcome)

      null
    }
  }

  private object AsyncK extends IOCont(2) {
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

          asyncContinue(state, Left(result.asInstanceOf[Throwable]))

          null
        }
      } else {
        if (isUnmasked()) {
          result.asInstanceOf[Option[IO[Unit]]] match {
            case Some(cancelToken) =>
              pushFinalizer(cancelToken)

              if (!state.compareAndSet(AsyncStateInitial, AsyncStateRegisteredWithFinalizer)) {
                // the callback was invoked before registration
                popFinalizer()
                asyncContinue(state, state.get().result)
              } else {
                suspendAndCheckCancellation()
              }

            case None =>
              if (!state.compareAndSet(AsyncStateInitial, AsyncStateRegisteredNoFinalizer)) {
                // the callback was invoked before registration
                asyncContinue(state, state.get().result)
              } else {
                suspendAndCheckCancellation()
              }
          }
        } else {
          // if we're masked, then don't even bother registering the cancel token
          if (!state.compareAndSet(AsyncStateInitial, AsyncStateRegisteredNoFinalizer)) {
            // the callback was invoked before registration
            asyncContinue(state, state.get().result)
          } else {
            suspendAndCheckCancellation()
          }
        }

        null
      }
    }
  }

  private object EvalOnK extends IOCont(3) {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      val ec = popContext()

      // special cancelation check to ensure we don't unnecessarily fork the runloop here
      if (!isCanceled() || !isUnmasked()) {
        execute(ec) { () =>
          if (success)
            runLoop(succeeded(result, 0), 0)
          else
            runLoop(failed(result, 0), 0)
        }
      } else {
        asyncCancel(null)
      }

      null
    }
  }

  // NB: this means repeated map is stack-unsafe
  private object MapK extends IOCont(4) {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      val f = popObjectState().asInstanceOf[Any => Any]

      var success = false

      val transformed =
        try {
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
          succeeded(transformed, depth + 1)
        else
          failed(transformed, depth + 1)
      }
    }
  }

  private object FlatMapK extends IOCont(5) {
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

  private object HandleErrorWithK extends IOCont(6) {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      val f = popObjectState().asInstanceOf[Throwable => IO[Any]]

      if (success) {
        // if it's *not* an error, just pass it along
        if (depth > MaxStackDepth)
          IO.Pure(result)
        else
          succeeded(result, depth + 1)
      } else {
        try {
          f(result.asInstanceOf[Throwable])
        } catch {
          case NonFatal(t) => failed(t, depth + 1)
        }
      }
    }
  }

  private object OnCancelK extends IOCont(7) {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      popFinalizer()

      if (success)
        succeeded(result, depth + 1)
      else
        failed(result, depth + 1)
    }
  }

  private object UncancelableK extends IOCont(8) {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      popMask()
      if (success)
        succeeded(result, depth + 1)
      else
        failed(result, depth + 1)
    }
  }

  private object UnmaskK extends IOCont(9) {
    def apply[A](self: IOFiber[A], success: Boolean, result: Any, depth: Int): IO[Any] = {
      import self._

      pushMask()
      if (success)
        succeeded(result, depth + 1)
      else
        failed(result, depth + 1)
    }
  }
}
