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
import scala.reflect.macros.blackbox

import cats.effect.kernel.Async
import cats.effect.kernel.syntax.all._
import cats.syntax.all._
import cats.effect.kernel.Outcome.Canceled
import cats.effect.kernel.Outcome.Errored
import cats.effect.kernel.Outcome.Succeeded

/**
 * WARNING: This construct currently only works on scala 2 (2.12.12+ / 2.13.3+),
 * relies on an experimental compiler feature enabled by the -Xasync
 * scalac option, and should absolutely be considered unstable with
 * regards to backward compatibility guarantees (be that source or binary).
 *
 * Partially applied construct allowing for async/await semantics,
 * popularised in other programming languages.
 *
 * {{{
 * object dsl extends AsyncAwaitDsl[IO]
 * import dsl._
 *
 * val io: IO[Int] = ???
 * async { await(io) + await(io) }
 * }}}
 *
 * The code is transformed at compile time into a state machine
 * that sequentially calls upon a [[Dispatcher]] every time it reaches
 * an "await" block.
 *
 * The AsyncAwaitDsl construct also allows for parallel composition:
 *
 * {{{
 * object dsl extends AsyncAwaitDsl[IO]
 * import dsl._
 *
 * val io: IO[Int] = ???
 * parallel { await(io) + await(io) }
 * }}}
 *
 * in which case, the code is transformed into a `parMapN` call that uses
 * the Async instance associated to the effect.
 */
class AsyncAwaitDsl[F[_]](implicit F: Async[F]) {

  /**
   * Type member used by the macro expansion to recover what `F` is without typetags
   */
  type _AsyncContext[A] = F[A]

  /**
   * Value member used by the macro expansion to recover the Async instance associated to the block.
   */
  val _AsyncInstance: Async[F] = F

  /**
   * Non-blocking await the on result of `awaitable`. This may only be used directly within an enclosing `async` block.
   *
   * Internally, this transforms the remainder of the code in enclosing `async` block into a callback
   * that triggers upon a successful computation outcome. It does *not* block a thread.
   */
  @compileTimeOnly("[async] `await` must be enclosed in an `async` block")
  def await[T](awaitable: F[T]): T =
    ??? // No implementation here, as calls to this are translated by the macro.

  /**
   * Run the block of code `body` asynchronously. `body` may contain calls to `await` when the results of
   * a `F` are needed; this is translated into non-blocking code.
   */
  def async[T](body: => T): F[T] = macro AsyncAwaitDsl.asyncImpl[F, T]

  /**
   * Run the block of code `body` by parallelising all `F`s that are wrapped in "await".
   * This is translated into a call to `parMapN`.
   *
   * Any term (val, var, def) defined in the `parallel` block cannot be used within `await` blocks.
   */
  def parallel[T](body: => T): F[T] = macro AsyncAwaitDsl.parallelImpl[F, T]

}

object AsyncAwaitDsl {

  type AwaitCallback[F[_]] = Either[Throwable, F[AnyRef]] => Unit

  // Outcome of an await block. Either a failed algebraic computation,
  // or a successful value accompanied by a "summary" computation.
  //
  // Allows to short-circuit the async/await state machine when relevant
  // (think OptionT.none) and track algebraic information that may otherwise
  // get lost during Dispatcher#unsafeRun calls (WriterT/IorT logs).
  type AwaitOutcome[F[_]] = Either[F[AnyRef], (F[Unit], AnyRef)]

  def asyncImpl[F[_], T](
      c: blackbox.Context
  )(body: c.Expr[T]): c.Expr[F[T]] = {
    import c.universe._
    if (!c.compilerSettings.contains("-Xasync")) {
      c.abort(
        c.macroApplication.pos,
        "The async method requires the compiler option -Xasync (supported only by Scala 2.12.12+ / 2.13.3+)"
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
        val tree = q"""
          final class $name(dispatcher: _root_.cats.effect.std.Dispatcher[${c.prefix}._AsyncContext], callback: _root_.cats.effect.std.AsyncAwaitDsl.AwaitCallback[${c.prefix}._AsyncContext]) extends _root_.cats.effect.std.AsyncAwaitStateMachine(dispatcher, callback) {
            ${mark(q"""override def apply(tr$$async: _root_.cats.effect.std.AsyncAwaitDsl.AwaitOutcome[${c.prefix}._AsyncContext]): _root_.scala.Unit = ${body}""")}
          }
          ${c.prefix}._AsyncInstance.flatten {
            _root_.cats.effect.std.Dispatcher[${c.prefix}._AsyncContext].use { dispatcher =>
              ${c.prefix}._AsyncInstance.async_[${c.prefix}._AsyncContext[AnyRef]](cb => new $name(dispatcher, cb).start())
            }
          }.asInstanceOf[${c.macroApplication.tpe}]
        """
        // format: on
        c.Expr(tree)
      } catch {
        case e: ReflectiveOperationException =>
          c.abort(
            c.macroApplication.pos,
            "-Xasync is provided as a Scala compiler option, but the async macro is unable to call c.internal.markForAsyncTransform. " + e
              .getClass
              .getName + " " + e.getMessage
          )
      }
  }

  def parallelImpl[F[_], T](c: blackbox.Context)(body: c.Expr[T]): c.Expr[F[T]] =
    parallelImpl0[F, T](c)(body.tree)

  def parallelImpl0[F[_], T](c: blackbox.Context)(body: c.Tree): c.Expr[F[T]] = {
    import c.universe._
    if (!c.compilerSettings.contains("-Xasync")) {
      c.abort(
        c.macroApplication.pos,
        "The parallel method requires the compiler option -Xasync (supported only by Scala 2.12.12+ / 2.13.3+)"
      )
    }

    def rec(t: Tree): Iterator[c.Tree] = Iterator(t) ++ t.children.flatMap(rec(_))

    val bound = collection.mutable.Buffer.empty[(c.Tree, ValDef)]
    val awaitSym = typeOf[AsyncAwaitDsl[Any]].decl(TermName("await"))

    // Derived from @olafurpg's
    // https://gist.github.com/olafurpg/596d62f87bf3360a29488b725fbc7608
    val defs = rec(body).filter(_.isDef).map(_.symbol).toSet

    val transformed = c.internal.typingTransform(body) {
      case (tt @ c.universe.Apply(_, fun :: Nil), _) if tt.symbol == awaitSym =>
        val localDefs = rec(fun).filter(_.isDef).map(_.symbol).toSet
        val banned = rec(tt).filter(x => defs(x.symbol) && !localDefs(x.symbol))

        if (banned.hasNext) {
          val banned0 = banned.next()
          c.abort(
            banned0.pos,
            "await(..) call cannot use `" + banned0.symbol + "` defined within the parallel{...} block"
          )
        }
        val tempName = c.freshName(TermName("tmp"))
        val tempSym =
          c.internal.newTermSymbol(c.internal.enclosingOwner, tempName)
        c.internal.setInfo(tempSym, tt.tpe)
        val tempIdent = Ident(tempSym)
        c.internal.setType(tempIdent, tt.tpe)
        c.internal.setFlag(tempSym, (1L << 44).asInstanceOf[c.universe.FlagSet])
        bound.append((fun, c.internal.valDef(tempSym)))
        tempIdent
      case (tt, api) => api.default(tt)
    }

    val (exprs, bindings) = bound.unzip
    val callback =
      c.typecheck(
        q"(..$bindings) => ${c.prefix}._AsyncInstance.delay { val res = $transformed; res }")
    val parMapMethod = TermName("parMap" + exprs.size)
    val res = if (exprs.size >= 2) {
      q"""
        _root_.cats.Parallel.$parMapMethod(..$exprs)($callback)(cats.effect.kernel.instances.spawn.parallelForGenSpawn(${c.prefix}._AsyncInstance)).flatten
      """
    } else if (exprs.size == 1) {
      q"""${c.prefix}._AsyncInstance.flatMap(..${exprs.head})($callback)"""
    } else {
      q"""$callback()"""
    }

    c.internal.changeOwner(transformed, c.internal.enclosingOwner, callback.symbol)

    c.Expr[F[T]](res)
  }

}

abstract class AsyncAwaitStateMachine[F[_]](
    dispatcher: Dispatcher[F],
    callback: AsyncAwaitDsl.AwaitCallback[F]
)(implicit F: Async[F])
    extends Function1[AsyncAwaitDsl.AwaitOutcome[F], Unit] {

  // FSM translated method
  //def apply(v1: AsyncAwaitDsl.AwaitOutcome[F]): Unit = ???

  // Resorting to mutation to track algebraic product effects (like WriterT),
  // since the information they carry would otherwise get lost on every dispatch.
  private[this] var summary: F[Unit] = F.unit

  private[this] var state$async: Int = 0

  /**
   * Retrieve the current value of the state variable
   */
  protected def state: Int = state$async

  /**
   * Assign `i` to the state variable
   */
  protected def state_=(s: Int): Unit = state$async = s

  protected def completeFailure(t: Throwable): Unit =
    callback(Left(t))

  protected def completeSuccess(value: AnyRef): Unit =
    callback(Right(F.as(summary, value)))

  protected def onComplete(f: F[AnyRef]): Unit = {
    dispatcher.unsafeRunAndForget {
      // Resorting to mutation to extract the "happy path" value from the monadic context,
      // as inspecting the Succeeded outcome using dispatcher is risky on algebraic sums,
      // such as OptionT, EitherT, ...
      var awaitedValue: Option[AnyRef] = None
      F.uncancelable { poll =>
        poll(summary *> f)
          .flatTap(r => F.delay { awaitedValue = Some(r) })
          .start
          .flatMap(fiber => poll(fiber.join).onCancel(fiber.cancel))
      }.flatMap {
        case Canceled() => F.delay(this(Left(F.canceled.asInstanceOf[F[AnyRef]])))
        case Errored(e) => F.delay(this(Left(F.raiseError(e))))
        case Succeeded(awaitOutcome) =>
          awaitedValue match {
            case Some(v) => F.delay(this(Right(awaitOutcome.void -> v)))
            case None => F.delay(this(Left(awaitOutcome)))
          }
      }
    }
  }

  protected def getCompleted(f: F[AnyRef]): AsyncAwaitDsl.AwaitOutcome[F] = {
    val _ = f
    null
  }

  protected def tryGet(awaitOutcome: AsyncAwaitDsl.AwaitOutcome[F]): AnyRef =
    awaitOutcome match {
      case Right((newSummary, value)) =>
        summary = newSummary
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
