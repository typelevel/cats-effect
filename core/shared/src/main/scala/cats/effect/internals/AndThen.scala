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

import cats.effect.IO

/**
 * A type-aligned seq for representing function composition in
 * constant stack space with amortized linear time application (in the
 * number of constituent functions).
 * 
 * Implementation is enormously uglier than it should be since
 * `@tailrec` doesn't work properly on functions with existential
 * types.
 */
private[effect] sealed abstract class AndThen[-A, +B] extends Product with Serializable {
  import AndThen._

  final def apply(a: A): B = {
    var self: AndThen[Any, Any] = this.asInstanceOf[AndThen[Any, Any]]
    var cur: Any = a.asInstanceOf[Any]
    var continue = true
    while (continue) {
      self match {
        case Single(f) =>
          cur = f(cur).asInstanceOf[Any]
          continue = false

        case Concat(Single(f), right) =>
          cur = f(cur).asInstanceOf[Any]
          self = right.asInstanceOf[AndThen[Any, Any]]

        case Concat(left @ Concat(_, _), right) => self = left.rotateAccum(right)

        case Concat(ss, right) =>
          val left = ss.asInstanceOf[ShortCircuit[Any, Any]]

          cur match {
            case Left(t: Throwable) =>
              cur = IO.pure(Left(t))
              self = right.asInstanceOf[AndThen[Any, Any]]

            case Right(a) =>
              self = left.inner.andThen(right).asInstanceOf[AndThen[Any, Any]]
              cur = a.asInstanceOf[Any]

            case _ => throw new AssertionError("types got screwy somewhere halp!!!")
          }

        case ss =>
          val ssc = ss.asInstanceOf[ShortCircuit[Any, Any]]

          cur match {
            case Left(t: Throwable) =>
              cur = IO.pure(Left(t))
              continue = false

            case Right(a) =>
              self = ssc.inner.asInstanceOf[AndThen[Any, Any]]
              cur = a.asInstanceOf[Any]

            case _ => throw new AssertionError("types got screwy somewhere halp!!!")
          }
      }
    }

    cur.asInstanceOf[B]
  }

  final def andThen[X](right: AndThen[B, X]): AndThen[A, X] = Concat(this, right)
  final def compose[X](right: AndThen[X, A]): AndThen[X, B] = Concat(right, this)

  final def shortCircuit[E](implicit ev: B <:< IO[Either[Throwable, E]]) = {
    val _ = ev

    ShortCircuit[A, E](this.asInstanceOf[AndThen[A, IO[Either[Throwable, E]]]])
  }

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

        // either Single or ShortCircuit; the latter doesn't typecheck
        case _ =>
          self = self.andThen(right)
          continue = false
      }
    }

    self.asInstanceOf[AndThen[A, E]]
  }

  override def toString = "AndThen$" + System.identityHashCode(this)
}

private[effect] object AndThen {

  def apply[A, B](f: A => B): AndThen[A, B] = Single(f)

  final case class Single[-A, +B](f: A => B) extends AndThen[A, B]
  final case class Concat[-A, E, +B](left: AndThen[A, E], right: AndThen[E, B]) extends AndThen[A, B]
  final case class ShortCircuit[-A, +B](inner: AndThen[A, IO[Either[Throwable, B]]]) extends AndThen[Either[Throwable, A], IO[Either[Throwable, B]]]
}
