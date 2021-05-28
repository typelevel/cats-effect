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

package cats.effect.std

import scala.annotation.compileTimeOnly
import scala.reflect.macros.whitebox

import cats.effect.kernel.Async
import cats.effect.kernel.syntax.all._
import cats.syntax.all._
import cats.effect.kernel.Outcome.Canceled
import cats.effect.kernel.Outcome.Errored
import cats.effect.kernel.Outcome.Succeeded

class AsyncAwaitDsl[F[_]](implicit F: Async[F]) {

  /**
   * Type member used by the macro expansion to recover what `F` is without typetags
   */
  type _AsyncContext[A] = F[A]

  /**
   * Value member used by the macro expansion to recover the Async instance associated to the block.
   */
  implicit val _AsyncInstance: Async[F] = F

  /**
   * Non-blocking await the on result of `awaitable`. This may only be used directly within an enclosing `async` block.
   *
   * Internally, this will register the remainder of the code in enclosing `async` block as a callback
   * in the `onComplete` handler of `awaitable`, and will *not* block a thread.
   */
  @compileTimeOnly("[async] `await` must be enclosed in an `async` block")
  def await[T](awaitable: F[T]): T =
    ??? // No implementation here, as calls to this are translated to `onComplete` by the macro.

  /**
   * Run the block of code `body` asynchronously. `body` may contain calls to `await` when the results of
   * a `Future` are needed; this is translated into non-blocking code.
   */
  def async[T](body: => T): F[T] = macro AsyncAwaitDsl.asyncImpl[F, T]

}

object AsyncAwaitDsl {

  type CallbackTarget[F[_]] = F[AnyRef]
  type Callback[F[_]] = Either[Throwable, CallbackTarget[F]] => Unit
  type ResumeOutcome[F[_]] = Either[F[AnyRef], (F[Unit], AnyRef)]

  def asyncImpl[F[_], T](
      c: whitebox.Context
  )(body: c.Tree): c.Tree = {
    import c.universe._
    if (!c.compilerSettings.contains("-Xasync")) {
      c.abort(
        c.macroApplication.pos,
        "The async requires the compiler option -Xasync (supported only by Scala 2.12.12+ / 2.13.3+)"
      )
    } else
      try {
        val awaitSym = typeOf[AsyncAwaitDsl[Any]].decl(TermName("await"))
        def mark(t: DefDef): Tree = {
          c.internal
            .asInstanceOf[{
              def markForAsyncTransform(
                  owner: Symbol,
                  method: DefDef,
                  awaitSymbol: Symbol,
                  config: Map[String, AnyRef]
              ): DefDef
            }]
            .markForAsyncTransform(
              c.internal.enclosingOwner,
              t,
              awaitSym,
              Map.empty
            )
        }
        val name = TypeName("stateMachine$async")
        // format: off
        q"""
          final class $name(dispatcher: _root_.cats.effect.std.Dispatcher[${c.prefix}._AsyncContext], callback: _root_.cats.effect.std.AsyncAwaitDsl.Callback[${c.prefix}._AsyncContext]) extends _root_.cats.effect.std.AsyncAwaitStateMachine(dispatcher, callback) {
            ${mark(q"""override def apply(tr$$async: _root_.cats.effect.std.AsyncAwaitDsl.ResumeOutcome[${c.prefix}._AsyncContext]): _root_.scala.Unit = ${body}""")}
          }
          ${c.prefix}._AsyncInstance.flatten {
            _root_.cats.effect.std.Dispatcher[${c.prefix}._AsyncContext].use { dispatcher =>
              ${c.prefix}._AsyncInstance.async_[${c.prefix}._AsyncContext[AnyRef]](cb => new $name(dispatcher, cb).start())
            }
          }.asInstanceOf[${c.macroApplication.tpe}]
        """
      } catch {
        case e: ReflectiveOperationException =>
          c.abort(
            c.macroApplication.pos,
            "-Xasync is provided as a Scala compiler option, but the async macro is unable to call c.internal.markForAsyncTransform. " + e.getClass.getName + " " + e.getMessage
          )
      }
  }

}

abstract class AsyncAwaitStateMachine[F[_]](
    dispatcher: Dispatcher[F],
    callback: AsyncAwaitDsl.Callback[F]
)(implicit F: Async[F]) extends Function1[AsyncAwaitDsl.ResumeOutcome[F], Unit] {

  // FSM translated method
  //def apply(v1: AsyncAwaitDsl.ResumeOutcome[F]): Unit = ???

  // Resorting to mutation to track algebraic product effects (like WriterT),
  // since the information they carry would otherwise get lost on every dispatch.
  private[this] var recordedEffect : F[Unit] = F.unit

  private[this] var state$async: Int = 0

  /** Retrieve the current value of the state variable */
  protected def state: Int = state$async

  /** Assign `i` to the state variable */
  protected def state_=(s: Int): Unit = state$async = s

  protected def completeFailure(t: Throwable): Unit =
    callback(Left(t))

  protected def completeSuccess(value: AnyRef): Unit = {
    callback(Right(F.as(recordedEffect, value)))
  }

  protected def onComplete(f: F[AnyRef]): Unit = {
    dispatcher.unsafeRunAndForget {
      // Resorting to mutation to extract the "happy path" value from the monadic context,
      // as inspecting the Succeeded outcome using dispatcher is risky on algebraic sums,
      // such as OptionT, EitherT, ...
      var value: Option[AnyRef] = None
      (recordedEffect *> f).flatTap(r => F.delay{value = Some(r)}).start.flatMap(_.join).flatMap {
        case Canceled() => F.delay(this(Left(F.canceled.asInstanceOf[F[AnyRef]])))
        case Errored(e) => F.delay(this(Left(F.raiseError(e))))
        case Succeeded(resumed) => value match {
          case Some(value) => F.delay(this(Right(resumed.void -> value)))
          case None => F.delay(this(Left(resumed)))
        }
      }
    }
  }

  protected def getCompleted(f: F[AnyRef]): AsyncAwaitDsl.ResumeOutcome[F] = {
    val _ = f
    null
  }

  protected def tryGet(tr: AsyncAwaitDsl.ResumeOutcome[F]): AnyRef =
    tr match {
      case Right((newEffect, value)) =>
        recordedEffect = newEffect
        value
      case Left(monadicStop) =>
        callback(Right(monadicStop))
        this // sentinel value to indicate the dispatch loop should exit.
    }

  def start(): Unit = {
    // Required to kickstart the async state machine.
    // `def apply` does not consult its argument when `state == 0`.
    apply(null)
  }

}
