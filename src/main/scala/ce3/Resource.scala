/*
 * Copyright 2020 Daniel Spiewak
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

package ce3

import cats.ApplicativeError
import cats.implicits._
import scala.annotation.tailrec
import cats.Functor
import cats.Applicative
import cats.Monad
import cats.data.AndThen

sealed abstract class Resource[+F[_], +A] {
  import Resource.{Allocate, Bind, Suspend}

  private def fold[G[x] >: F[x], B, E](
      onOutput: A => G[B],
      onRelease: G[Unit] => G[Unit]
  )(implicit F: Bracket[G, E]): G[B] = {
    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue(
        current: Resource[G, Any],
        stack: List[Any => Resource[G, Any]]
    ): G[Any] =
      loop(current, stack)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop(
        current: Resource[G, Any],
        stack: List[Any => Resource[G, Any]]
    ): G[Any] =
      current match {
        case a: Allocate[G, F.Case, Any] @unchecked =>
          F.bracketCase(a.resource) {
            case (a, _) =>
              stack match {
                case Nil => onOutput.asInstanceOf[Any => G[Any]](a)
                case l   => continue(l.head(a), l.tail)
              }
          } {
            case ((_, release), ec) =>
              onRelease(release(ec))
          }
        case b: Bind[G, _, Any] =>
          loop(b.source, b.fs.asInstanceOf[Any => Resource[G, Any]] :: stack)
        case s: Suspend[G, Any] =>
          s.resource.flatMap(continue(_, stack))
      }
    loop(this.asInstanceOf[Resource[G, Any]], Nil).asInstanceOf[G[B]]
  }

  def use[G[x] >: F[x], B, E](
      f: A => G[B]
  )(implicit F: Bracket[G, E]): G[B] =
    fold[G, B, E](f, identity)
  //todo: allocated?

  def used[G[x] >: F[x], E](implicit F: Bracket[G, E]): G[Unit] =
    use(_ => F.unit)
}

object Resource {

  /**
    * Creates a resource from an allocating effect.
    *
    * @see [[make]] for a version that separates the needed resource
    *      with its finalizer tuple in two parameters
    *
    * @tparam F the effect type in which the resource is acquired and released
    * @tparam A the type of the resource
    * @param resource an effect that returns a tuple of a resource and
    *        an effect to release it
    */
  def apply[F[_], A, E](
      resource: F[(A, F[Unit])]
  )(implicit F: Bracket[F, E]): Resource[F, A] =
    applyCase[F, F.Case, E, A](resource.map(_.map(Function.const)))(F)

  /**
    * Creates a resource from an allocating effect, with a finalizer
    * that is able to distinguish between [[ExitCase exit cases]].
    *
    * @see [[makeCase]] for a version that separates the needed resource
    *      with its finalizer tuple in two parameters
    *
    * @tparam F the effect type in which the resource is acquired and released
    * @tparam A the type of the resource
    * @param resource an effect that returns a tuple of a resource and
    *        an effectful function to release it
    */
  def applyCase[F[_], Case[_], E, A](
      resource: F[(A, Case[_] => F[Unit])]
  )(implicit F: Bracket.Aux[F, E, Case]): Resource[F, A] =
    Allocate[F, Case, A](resource)

  def applyCase0[F[_], E](
      implicit bracket: Bracket[F, E]
  ): ApplyCasePartiallyApplied[F, bracket.Case, E] =
    new ApplyCasePartiallyApplied[F, bracket.Case, E](bracket)

  final class ApplyCasePartiallyApplied[F[_], Case[_], E](
      bracket: Bracket.Aux[F, E, Case]
  ) {
    def apply[A](resource: F[(A, Case[_] => F[Unit])]): Resource[F, A] =
      applyCase[F, Case, E, A](resource)(bracket)
  }

  /**
    * Given a `Resource` suspended in `F[_]`, lifts it in the `Resource` context.
    */
  def suspend[F[_], A](fr: F[Resource[F, A]]): Resource[F, A] =
    Resource.Suspend(fr)

  /**
    * Creates a resource from an acquiring effect and a release function.
    *
    * This builder mirrors the signature of [[Bracket.bracket]].
    *
    * @tparam F the effect type in which the resource is acquired and released
    * @tparam A the type of the resource
    * @param acquire a function to effectfully acquire a resource
    * @param release a function to effectfully release the resource returned by `acquire`
    */
  def make[F[_], A, E](
      acquire: F[A]
  )(release: A => F[Unit])(implicit F: Bracket[F, E]): Resource[F, A] =
    apply[F, A, E](acquire.map(a => a -> release(a)))

  /**
    * Creates a resource from an acquiring effect and a release function that can
    * discriminate between different [[ExitCase exit cases]].
    *
    * This builder mirrors the signature of [[Bracket.bracketCase]].
    *
    * @tparam F the effect type in which the resource is acquired and released
    * @tparam A the type of the resource
    * @param acquire a function to effectfully acquire a resource
    * @param release a function to effectfully release the resource returned by `acquire`
    */
  def makeCase[F[_], Case[_], E, A](
      acquire: F[A]
  )(
      release: (A, Case[_]) => F[Unit]
  )(implicit F: Bracket.Aux[F, E, Case]): Resource[F, A] =
    applyCase[F, Case, E, A](
      acquire.map(a => (a, e => release(a, e)))
    )

  /**
    * Lifts a pure value into a resource. The resource has a no-op release.
    *
    * @param a the value to lift into a resource
    */
  def pure[F[_], A](a: A)(implicit F: Applicative[F]): Resource[F, A] =
    Allocate[F, Any, A](F.pure((a, (_: Any) => F.unit)))

  /**
    * Lifts an applicative into a resource. The resource has a no-op release.
    * Preserves interruptibility of `fa`.
    *
    * @param fa the value to lift into a resource
    */
  def liftF[F[_], A](fa: F[A])(implicit F: Applicative[F]): Resource[F, A] =
    Resource.suspend(fa.map(a => Resource.pure[F, A](a)))

  /**
    * Implementation for the `tailRecM` operation, as described via
    * the `cats.Monad` type class.
    */
  def tailRecM[F[_], A, B, E](
      a: A
  )(
      f: A => Resource[F, Either[A, B]]
  )(implicit F: Bracket[F, E]): Resource[F, B] = {
    def continue(r: Resource[F, Either[A, B]]): Resource[F, B] =
      r match {
        case a: Allocate[F, F.Case, Either[A, B]] @unchecked =>
          Suspend(a.resource.flatMap[Resource[F, B]] {
            case (Left(a), release) =>
              release(F.CaseInstance.pure(a)).map(_ =>
                tailRecM[F, A, B, E](a)(f)
              )
            case (Right(b), release) =>
              F.pure(Allocate[F, F.Case, B](F.pure((b, release))))
          })
        case s: Suspend[F, Either[A, B]] =>
          Suspend(s.resource.map(continue))
        case b: Bind[F, _, Either[A, B]] =>
          Bind(b.source, AndThen(b.fs).andThen(continue))
      }

    continue(f(a))
  }

  /**
    * Lifts an applicative into a resource as a `FunctionK`. The resource has a no-op release.
    */
  // def liftK[F[_]](implicit F: Applicative[F]): F ~> Resource[F, *] =
  // λ[F ~> Resource[F, *]](Resource.liftF(_))

  /**
    * `Resource` data constructor that wraps an effect allocating a resource,
    * along with its finalizers.
    */
  final case class Allocate[F[_], Case[_], A](
      resource: F[(A, Case[_] => F[Unit])]
  ) extends Resource[F, A]

  /**
    * `Resource` data constructor that encodes the `flatMap` operation.
    */
  final case class Bind[F[_], S, +A](
      source: Resource[F, S],
      fs: S => Resource[F, A]
  ) extends Resource[F, A]

  /**
    * `Resource` data constructor that suspends the evaluation of another
    * resource value.
    */
  final case class Suspend[F[_], A](resource: F[Resource[F, A]])
      extends Resource[F, A]

  implicit def regionForResource[F[_], E](
      implicit F: Bracket[F, E]
  ): Region.Aux[Resource, F, E, F.Case] =
    new Region[Resource, F, E] {
      override type Case[A] = F.Case[A]

      def pure[A](x: A): Resource[F, A] = Resource.pure[F, A](x)

      def raiseError[A](e: E): Resource[F, A] = Resource.liftF(F.raiseError(e))

      def handleErrorWith[A](
          fa: Resource[F, A]
      )(f: E => Resource[F, A]): Resource[F, A] =
        flatMap(attempt(fa)) {
          case Right(a) => pure(a)
          case Left(e)  => f(e)
        }

      override def attempt[A](fa: Resource[F, A]): Resource[F, Either[E, A]] =
        fa match {
          case alloc: Allocate[F, F.Case, b] @unchecked =>
            Allocate[F, F.Case, Either[E, A]](
              F.map(F.attempt(alloc.resource)) {
                case Left(error) =>
                  (error.asLeft[A], (_: F.Case[_]) => F.unit)
                case Right((a, release)) => (Right(a), release)
              }
            )
          case Bind(
              source: Resource[F, Any] @unchecked,
              fs: (Any => Resource[F, A]) @unchecked
              ) =>
            Suspend(F.pure(source).map[Resource[F, Either[E, A]]] { source =>
              Bind(
                attempt(source),
                (r: Either[E, Any]) =>
                  r match {
                    case Left(error) => pure(Left(error))
                    case Right(s) => attempt(fs(s))
                  }
              )
            })

          case s: Suspend[F, a] =>
            Suspend(F.map(F.attempt(s.resource)) {
              case Left(error) => pure(Left(error))
              case Right(fa)   => attempt(fa)
            })
        }

      def flatMap[A, B](fa: Resource[F, A])(
          f: A => Resource[F, B]
      ): Resource[F, B] = Bind(fa, f)

      def tailRecM[A, B](
          a: A
      )(f: A => Resource[F, Either[A, B]]): Resource[F, B] =
        Resource.tailRecM(a)(f)

      implicit val CaseInstance: ApplicativeError[Case, E] =
        F.CaseInstance

      def openCase[A](acquire: F[A])(
          release: (A, Case[_]) => F[Unit]
      ): Resource[F, A] =
        Allocate[F, Case, A](acquire.map(a => (a, release(a, _))))

      def liftF[A](fa: F[A]): Resource[F, A] = Resource.liftF(fa)

      def supersededBy[B](
          rfa: Resource[F, _],
          rfb: Resource[F, B]
      ): Resource[F, B] = Resource.liftF(rfa.use(_ => F.unit)) *> rfb

    }
}
