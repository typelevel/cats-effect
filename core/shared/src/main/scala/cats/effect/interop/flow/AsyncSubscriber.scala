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

package cats.effect.interop.flow

import cats.effect.kernel.Sync

import java.util.Objects.requireNonNull
import java.util.concurrent.Flow.{Subscriber, Subscription}
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NoStackTrace

/**
 * Implementation of a [[Subscriber]].
 *
 * This is used to obtain an effect from an upstream reactive-streams system.
 *
 * @see
 *   [[https://github.com/reactive-streams/reactive-streams-jvm#2-subscriber-code]]
 */
private[flow] final class AsyncSubscriber[F[_], A] private (
    cb: Either[Throwable, Option[A]] => Unit,
    currentState: AtomicReference[(AsyncSubscriber.State, () => Unit)]
)(
    implicit F: Sync[F]
) extends Subscriber[A] {
  import AsyncSubscriber.State._
  import AsyncSubscriber.Input._
  import AsyncSubscriber.InvalidStateException

  // Subscriber API.

  /**
   * Receives a subscription from the upstream reactive-streams system.
   */
  override final def onSubscribe(subscription: Subscription): Unit = {
    requireNonNull(
      subscription,
      "The subscription provided to onSubscribe must not be null"
    )
    nextState(input = Subscribe(subscription))
  }

  /**
   * Receives the next record from the upstream reactive-streams system.
   */
  override final def onNext(a: A): Unit = {
    requireNonNull(
      a,
      "The element provided to onNext must not be null"
    )
    nextState(input = Next(a))
  }

  /**
   * Called by the upstream reactive-streams system when it fails.
   */
  override final def onError(ex: Throwable): Unit = {
    requireNonNull(
      ex,
      "The throwable provided to onError must not be null"
    )
    nextState(input = Error(ex))
  }

  /**
   * Called by the upstream reactive-streams system when it has finished sending records.
   */
  override final def onComplete(): Unit = {
    nextState(input = Complete)
  }

  // Downstream cancellation.

  /**
   * Allow downstream to cancel this subscriber.
   */
  val cancel: F[Unit] =
    F.delay(nextState(input = Cancel))

  // Finite state machine.

  /**
   * Helper to reduce noise when creating unary functions.
   */
  private def run(block: => Unit): () => Unit = () => block

  /**
   * Helper to reduce noise when failing with an InvalidStateException.
   */
  private def invalidState(operation: String, state: State): Unit =
    cb(Left(new InvalidStateException(operation, state)))

  /**
   * Runs a single step of the state machine.
   */
  private def step(input: Input): State => (State, () => Unit) =
    input match {
      case Subscribe(s) => {
        // When subscribed:
        // If we are in the initial state:
        //   We request a single element.
        // Otherwise if we are in the canceled state:
        //   We cancel the subscription.
        // Otherwise:
        //   We cancel the subscription,
        //   and attempt to complete the callback with an InvalidStateException.
        case Initial =>
          Waiting(s) -> run {
            s.request(1L)
          }

        case Canceled =>
          Canceled -> run {
            s.cancel()
          }

        case state =>
          Completed -> run {
            s.cancel()
            invalidState(
              operation = "Received subscription",
              state
            )
          }
      }

      case Next(a) => {
        // When the element is received:
        // If we are in the waiting state:
        //   We attempt to complete the callback with the element,
        //   and cancel the upstream system.
        // Otherwise:
        //   We attempt to complete the callback with an InvalidStateException.
        case Waiting(s) =>
          Completed -> run {
            s.cancel()
            cb(Right(Some(a.asInstanceOf[A])))
          }

        case state =>
          Completed -> run {
            invalidState(
              operation = s"Received element [${a}]",
              state
            )
          }
      }

      case Error(ex) => {
        // When an error is received:
        // If we are in the waiting state:
        //   We attempt to complete the callback with the error.
        // Otherwise:
        //   We attempt to complete the callback with an InvalidStateException.
        case Waiting(_) =>
          Completed -> run {
            cb(Left(ex))
          }

        case state =>
          Completed -> run {
            invalidState(
              operation = s"Received error [${ex}]",
              state
            )
          }
      }

      case Complete => {
        // When a completion signal is received:
        // If we are in the waiting state:
        //   We attempt to complete the callback with a None,
        //   and cancel the upstream system.
        // Otherwise:
        //   We attempt to complete the callback with an InvalidStateException.
        case Waiting(s) =>
          Completed -> run {
            s.cancel()
            cb(Right(None))
          }

        case state =>
          Completed -> run {
            invalidState(
              operation = s"Received completion signal",
              state
            )
          }
      }

      case Cancel => {
        // When a downstream cancellation signal is received:
        // If we are in the waiting state:
        //   We cancel the upstream system.
        // Otherwise:
        //   We put ourselves in the Canceled state.
        case Waiting(s) =>
          Canceled -> run {
            s.cancel()
          }

        case _ =>
          Canceled -> (() => ())
      }
    }

  /**
   * Runs the next step of fsm.
   *
   * This function is concurrent safe, because the reactive-streams specs mention that all the
   * on methods are to be called sequentially. Additionally, `Dequeue` and `Next` can't never
   * happen concurrently, since they are tied together. Thus, these are the only concurrent
   * options and all are covered: + `Subscribe` & `Dequeue`: No matter the order in which they
   * are processed, we will end with a request call and a null buffer. + `Error` & `Dequeue`: No
   * matter the order in which they are processed, we will complete the callback with the error.
   * + cancellation & any other thing: Worst case, we will lose some data that we not longer
   * care about; and eventually reach `Terminal`.
   */
  private def nextState(input: Input): Unit = {
    val (_, effect) = currentState.updateAndGet {
      case (state, _) =>
        step(input)(state)
    }
    // Only run the effect after the state update took place.
    effect()
  }
}

private[flow] object AsyncSubscriber {
  def apply[F[_], A](
      cb: Either[Throwable, Option[A]] => Unit
  )(
      implicit F: Sync[F]
  ): F[AsyncSubscriber[F, A]] =
    F.delay {
      new AsyncSubscriber(
        cb,
        currentState = new AtomicReference(
          (State.Initial, () => ())
        )
      )
    }

  private sealed trait State
  private object State {
    type State = AsyncSubscriber.State

    case object Initial extends State
    final case class Waiting(s: Subscription) extends State
    case object Completed extends State
    case object Canceled extends State
  }

  private sealed trait Input
  private object Input {
    type Input = AsyncSubscriber.Input

    final case class Subscribe(s: Subscription) extends Input
    final case class Next(a: Any) extends Input
    final case class Error(ex: Throwable) extends Input
    case object Complete extends Input
    case object Cancel extends Input
  }

  private final class InvalidStateException(operation: String, state: State)
      extends IllegalStateException(
        s"${operation} in invalid state [${state}]"
      )
      with NoStackTrace
}
