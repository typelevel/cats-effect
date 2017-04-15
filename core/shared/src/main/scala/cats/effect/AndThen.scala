/*
 * Copyright 2017 Daniel Spiewak
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

package cats
package effect

import java.io.Serializable

/**
 * A type-aligned seq for representing function composition in constant stack space with
 * ammortized linear time application (in the number of constituent functions).  Implementation
 * is enormously uglier than it should be since `@tailrec` doesn't work properly on functions
 * with existential types.
 */
private[effect] sealed abstract class AndThen[-A, +B] extends Product with Serializable {
  import AndThen._

  final def apply(a: A): B = {
    /*
    // this is the aspirational code, but doesn't work due to tailrec bugs
    this match {
      case Single(f) => f(a)
      case Concat(Single(f), right) => right(f(a))
      case Concat(left, right) => left.rotateAccum(right)(a)
    }
    */

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

        case Concat(left, right) => self = left.rotateAccum(right)
      }
    }

    cur.asInstanceOf[B]
  }

  final def andThen[X](right: AndThen[B, X]): AndThen[A, X] = Concat(this, right)
  final def compose[X](right: AndThen[X, A]): AndThen[X, B] = Concat(right, this)

  // converts left-leaning to right-leaning
  private final def rotateAccum[E](_right: AndThen[B, E]): AndThen[A, E] = {
    /*
    // this is the aspirational code, but doesn't work due to tailrec bugs
    this match {
      case Single(f) => this.andThen(right)
      case Concat(left, inner) => left.rotateAccum(inner.andThen(right))
    }
    */

    var self: AndThen[Any, Any] = this.asInstanceOf[AndThen[Any, Any]]
    var right: AndThen[Any, Any] = _right.asInstanceOf[AndThen[Any, Any]]
    var continue = true
    while (continue) {
      self match {
        case Single(f) =>
          self = self.andThen(right)
          continue = false

        case Concat(left, inner) =>
          self = left.asInstanceOf[AndThen[Any, Any]]
          right = inner.andThen(right)
      }
    }

    self.asInstanceOf[AndThen[A, E]]
  }
}

private[effect] object AndThen {

  def apply[A, B](f: A => B): AndThen[A, B] = Single(f)

  final case class Single[-A, +B](f: A => B) extends AndThen[A, B]
  final case class Concat[-A, E, +B](left: AndThen[A, E], right: AndThen[E, B]) extends AndThen[A, B]
}
