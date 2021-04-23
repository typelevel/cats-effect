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

import cats.effect.kernel.Outcome
import cats.effect.kernel.Sync
import cats.effect.kernel.Async
import cats.effect.kernel.syntax.all._

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

  type Callback = Either[Throwable, AnyRef] => Unit

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
          import language.reflectiveCalls
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
          final class $name(dispatcher: _root_.cats.effect.std.Dispatcher[${c.prefix}._AsyncContext], callback: _root_.cats.effect.std.AsyncAwaitDsl.Callback) extends _root_.cats.effect.std.AsyncAwaitStateMachine(dispatcher, callback) {
            ${mark(q"""override def apply(tr$$async: _root_.cats.effect.kernel.Outcome[${c.prefix}._AsyncContext, _root_.scala.Throwable, _root_.scala.AnyRef]): _root_.scala.Unit = ${body}""")}
          }
          ${c.prefix}._AsyncInstance.recoverWith {
            _root_.cats.effect.std.Dispatcher[${c.prefix}._AsyncContext].use { dispatcher =>
              ${c.prefix}._AsyncInstance.async_[_root_.scala.AnyRef](cb => new $name(dispatcher, cb).start())
            }
          }{
            case _root_.cats.effect.std.AsyncAwaitDsl.CancelBridge =>
              ${c.prefix}._AsyncInstance.map(${c.prefix}._AsyncInstance.canceled)(_ => null.asInstanceOf[AnyRef])
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

  // A marker exception to communicate cancellation through the async runtime.
  object CancelBridge extends Throwable with scala.util.control.NoStackTrace
}

abstract class AsyncAwaitStateMachine[F[_]](
    dispatcher: Dispatcher[F],
    callback: AsyncAwaitDsl.Callback
)(implicit F: Sync[F]) extends Function1[Outcome[F, Throwable, AnyRef], Unit] {

  // FSM translated method
  //def apply(v1: Outcome[IO, Throwable, AnyRef]): Unit = ???

  private[this] var state$async: Int = 0

  /** Retrieve the current value of the state variable */
  protected def state: Int = state$async

  /** Assign `i` to the state variable */
  protected def state_=(s: Int): Unit = state$async = s

  protected def completeFailure(t: Throwable): Unit =
    callback(Left(t))

  protected def completeSuccess(value: AnyRef): Unit = {
    callback(Right(value))
  }

  protected def onComplete(f: F[AnyRef]): Unit = {
    dispatcher.unsafeRunAndForget(f.guaranteeCase(outcome => F.delay(this(outcome))))
  }

  protected def getCompleted(f: F[AnyRef]): Outcome[F, Throwable, AnyRef] = {
    val _ = f
    null
  }

  protected def tryGet(tr: Outcome[F, Throwable, AnyRef]): AnyRef =
    tr match {
      case Outcome.Succeeded(value) =>
        // TODO discuss how to propagate "errors"" from other
        // error channels than the Async's, such as None
        // in OptionT. Maybe some ad-hoc polymorphic construct
        // with a custom path-dependent "bridge" exception type...
        // ... or something
        dispatcher.unsafeRunSync(value)
      case Outcome.Errored(e) =>
        callback(Left(e))
        this // sentinel value to indicate the dispatch loop should exit.
      case Outcome.Canceled() =>
        callback(Left(AsyncAwaitDsl.CancelBridge))
        this
    }

  def start(): Unit = {
    // Required to kickstart the async state machine.
    // `def apply` does not consult its argument when `state == 0`.
    apply(null)
  }

}
