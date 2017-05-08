/*
 * Copyright 2017 Typelevel
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

package cats.effect.internals

import java.io.Serializable

/**
 * A type-aligned seq for representing function composition in
 * constant stack space with amortized linear time application (in the
 * number of constituent functions).
 *
 * Implementation is enormously uglier than it should be since
 * `tailrec` doesn't work properly on functions with existential
 * types.
 */
private[effect] sealed abstract class AndThen[-A, +B] extends Product with Serializable {
  import AndThen._

  final def apply(a: A): B =
    runLoop(a, null, isSuccess = true)

  final def error[R >: B](e: Throwable, orElse: Throwable => R): R =
    try runLoop(null.asInstanceOf[A], e, isSuccess = false)
    catch { case NonFatal(e2) => orElse(e2) }

  private def runLoop(success: A, failure: Throwable, isSuccess: Boolean): B = {
    var self: AndThen[Any, Any] = this.asInstanceOf[AndThen[Any, Any]]
    var successRef: Any = success.asInstanceOf[Any]
    var failureRef = failure
    var hasSuccessRef = isSuccess
    var continue = true

    def processSuccess(f: (Any) => Any): Unit =
      try successRef = f(successRef) catch {
        case NonFatal(e) =>
          failureRef = e
          hasSuccessRef = false
      }

    def processError(f: Throwable => Any): Unit =
      try {
        successRef = f(failureRef)
        hasSuccessRef = true
      } catch {
        case NonFatal(e2) => failureRef = e2
      }

    while (continue) {
      self match {
        case Single(f) =>
          if (hasSuccessRef) processSuccess(f)
          continue = false

        case Concat(Single(f), right) =>
          if (hasSuccessRef) processSuccess(f)
          self = right.asInstanceOf[AndThen[Any, Any]]

        case Concat(left @ Concat(_, _), right) =>
          self = left.rotateAccum(right)

        case Concat(ErrorHandler(fa, fe), right) =>
          if (hasSuccessRef) processSuccess(fa)
          else processError(fe)
          self = right.asInstanceOf[AndThen[Any, Any]]

        case ErrorHandler(fa, fe) =>
          if (hasSuccessRef) processSuccess(fa)
          else processError(fe)
          continue = false
      }
    }

    if (hasSuccessRef) successRef.asInstanceOf[B]
    else throw failureRef
  }

  final def andThen[X](right: AndThen[B, X]): AndThen[A, X] = Concat(this, right)
  final def compose[X](right: AndThen[X, A]): AndThen[X, B] = Concat(right, this)

  // converts left-leaning to right-leaning
  protected final def rotateAccum[E](_right: AndThen[B, E]): AndThen[A, E] = {
    var self: AndThen[Any, Any] = this.asInstanceOf[AndThen[Any, Any]]
    var right: AndThen[Any, Any] = _right.asInstanceOf[AndThen[Any, Any]]
    var continue = true
    while (continue) {
      self match {
        case Concat(left, inner) =>
          self = left.asInstanceOf[AndThen[Any, Any]]
          right = inner.andThen(right)

        // Either Single or ErrorHandler
        case _ =>
          self = self.andThen(right)
          continue = false
      }
    }

    self.asInstanceOf[AndThen[A, E]]
  }

  override def toString =
    "AndThen$" + System.identityHashCode(this)
}

private[effect] object AndThen {
  /** Builds simple [[AndThen]] reference by wrapping a function. */
  def apply[A, B](f: A => B): AndThen[A, B] =
    Single(f)

  /** Builds [[AndThen]] reference that can handle errors. */
  def apply[A, B](fa: A => B, fe: Throwable => B): AndThen[A, B] =
    ErrorHandler(fa, fe)

  final case class Single[-A, +B](f: A => B) extends AndThen[A, B]
  final case class ErrorHandler[-A, +B](fa: A => B, fe: Throwable => B) extends AndThen[A, B]
  final case class Concat[-A, E, +B](left: AndThen[A, E], right: AndThen[E, B]) extends AndThen[A, B]
}
