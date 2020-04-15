/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

import cats._
import cats.data.AndThen
import cats.effect.ExitCase.Completed
import cats.effect.concurrent.Ref
import cats.effect.internals.ResourcePlatform
import cats.implicits._
import cats.effect.implicits._

import scala.annotation.tailrec

/**
 * The `Resource` is a data structure that captures the effectful
 * allocation of a resource, along with its finalizer.
 *
 * This can be used to wrap expensive resources. Example:
 *
 * {{{
 *   def open(file: File): Resource[IO, BufferedReader] =
 *     Resource(IO {
 *       val in = new BufferedReader(new FileReader(file))
 *       (in, IO(in.close()))
 *     })
 * }}}
 *
 * Usage is done via [[Resource!.use use]] and note that resource usage nests,
 * because its implementation is specified in terms of [[Bracket]]:
 *
 * {{{
 *   open(file1).use { in1 =>
 *     open(file2).use { in2 =>
 *       readFiles(in1, in2)
 *     }
 *   }
 * }}}
 *
 * `Resource` forms a `MonadError` on the resource type when the
 * effect type has a `cats.MonadError` instance. Nested resources are
 * released in reverse order of acquisition. Outer resources are
 * released even if an inner use or release fails.
 *
 * {{{
 *   def mkResource(s: String) = {
 *     val acquire = IO(println(s"Acquiring $$s")) *> IO.pure(s)
 *     def release(s: String) = IO(println(s"Releasing $$s"))
 *     Resource.make(acquire)(release)
 *   }
 *
 *   val r = for {
 *     outer <- mkResource("outer")
 *     inner <- mkResource("inner")
 *   } yield (outer, inner)
 *
 *   r.use { case (a, b) =>
 *     IO(println(s"Using $$a and $$b"))
 *   }
 * }}}
 *
 * On evaluation the above prints:
 * {{{
 *   Acquiring outer
 *   Acquiring inner
 *   Using outer and inner
 *   Releasing inner
 *   Releasing outer
 * }}}
 *
 * A `Resource` is nothing more than a data structure, an ADT, described by
 * the following node types and that can be interpreted if needed:
 *
 *  - [[cats.effect.Resource.Allocate Allocate]]
 *  - [[cats.effect.Resource.Suspend Suspend]]
 *  - [[cats.effect.Resource.Bind Bind]]
 *
 * Normally users don't need to care about these node types, unless conversions
 * from `Resource` into something else is needed (e.g. conversion from `Resource`
 * into a streaming data type).
 *
 * @tparam F the effect type in which the resource is allocated and released
 * @tparam A the type of resource
 */
sealed abstract class Resource[+F[_], +A] {
  import Resource.{Allocate, Bind, Suspend}

  private def fold[G[x] >: F[x], B](
    onOutput: A => G[B],
    onRelease: G[Unit] => G[Unit]
  )(implicit F: Bracket[G, Throwable]): G[B] = {
    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue(current: Resource[G, Any], stack: List[Any => Resource[G, Any]]): G[Any] =
      loop(current, stack)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop(current: Resource[G, Any], stack: List[Any => Resource[G, Any]]): G[Any] =
      current match {
        case a: Allocate[G, Any] =>
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

  /**
   * Allocates a resource and supplies it to the given function.
   * The resource is released as soon as the resulting `F[B]` is
   * completed, whether normally or as a raised error.
   *
   * @param f the function to apply to the allocated resource
   * @return the result of applying [F] to
   */
  def use[G[x] >: F[x], B](f: A => G[B])(implicit F: Bracket[G, Throwable]): G[B] =
    fold[G, B](f, identity)

  /**
   * Allocates two resources concurrently, and combines their results in a tuple.
   *
   * The finalizers for the two resources are also run concurrently with each other,
   * but within _each_ of the two resources, nested finalizers are run in the usual
   * reverse order of acquisition.
   *
   * Note that `Resource` also comes with a `cats.Parallel` instance
   * that offers more convenient access to the same functionality as
   * `parZip`, for example via `parMapN`:
   *
   * {{{
   *   def mkResource(name: String) = {
   *     val acquire =
   *       IO(scala.util.Random.nextInt(1000).millis).flatMap(IO.sleep) *>
   *       IO(println(s"Acquiring $$name")).as(name)
   *
   *     val release = IO(println(s"Releasing $$name"))
   *     Resource.make(acquire)(release)
   *   }
   *
   *  val r = (mkResource("one"), mkResource("two"))
   *             .parMapN((s1, s2) => s"I have \$s1 and \$s2")
   *             .use(msg => IO(println(msg)))
   * }}}
   *
   **/
  def parZip[G[x] >: F[x]: Sync: Parallel, B](
    that: Resource[G, B]
  ): Resource[G, (A, B)] = {
    type Update = (G[Unit] => G[Unit]) => G[Unit]

    def allocate[C](r: Resource[G, C], storeFinalizer: Update): G[C] =
      r.fold[G, C](_.pure[G], release => storeFinalizer(_.guarantee(release)))

    val bothFinalizers = Ref[G].of(Sync[G].unit -> Sync[G].unit)

    Resource
      .make(bothFinalizers)(_.get.flatMap(_.parTupled).void)
      .evalMap { store =>
        val leftStore: Update = f => store.update(_.leftMap(f))
        val rightStore: Update = f => store.update(_.map(f))

        (allocate(this, leftStore), allocate(that, rightStore)).parTupled
      }
  }

  /**
   * Implementation for the `flatMap` operation, as described via the
   * `cats.Monad` type class.
   */
  def flatMap[G[x] >: F[x], B](f: A => Resource[G, B]): Resource[G, B] =
    Bind(this, f)

  /**
   *  Given a mapping function, transforms the resource provided by
   *  this Resource.
   *
   *  This is the standard `Functor.map`.
   */
  def map[G[x] >: F[x], B](f: A => B)(implicit F: Applicative[G]): Resource[G, B] =
    flatMap(a => Resource.pure[G, B](f(a)))

  @deprecated("Use the overload that doesn't require Bracket", "2.2.0")
  private[effect] def mapK[G[x] >: F[x], H[_]](f: G ~> H,
                                               B: Bracket[G, Throwable],
                                               D: Defer[H],
                                               G: Applicative[H]): Resource[H, A] =
    this.mapK[G, H](f)(D, G)

  /**
   * Given a natural transformation from `F` to `G`, transforms this
   * Resource from effect `F` to effect `G`.
   */
  def mapK[G[x] >: F[x], H[_]](
    f: G ~> H
  )(implicit D: Defer[H], G: Applicative[H]): Resource[H, A] =
    this match {
      case Allocate(resource) =>
        Allocate(f(resource).map { case (a, r) => (a, r.andThen(u => f(u))) })
      case Bind(source, f0) =>
        Bind(Suspend(D.defer(G.pure(source.mapK(f)))), f0.andThen(_.mapK(f)))
      case Suspend(resource) =>
        Suspend(f(resource).map(_.mapK(f)))
    }

  /**
   * Given a `Resource`, possibly built by composing multiple
   * `Resource`s monadically, returns the acquired resource, as well
   * as an action that runs all the finalizers for releasing it.
   *
   * If the outer `F` fails or is interrupted, `allocated` guarantees
   * that the finalizers will be called. However, if the outer `F`
   * succeeds, it's up to the user to ensure the returned `F[Unit]`
   * is called once `A` needs to be released. If the returned
   * `F[Unit]` is not called, the finalizers will not be run.
   *
   * For this reason, this is an advanced and potentially unsafe api
   * which can cause a resource leak if not used correctly, please
   * prefer [[use]] as the standard way of running a `Resource`
   * program.
   *
   * Use cases include interacting with side-effectful apis that
   * expect separate acquire and release actions (like the `before`
   * and `after` methods of many test frameworks), or complex library
   * code that needs to modify or move the finalizer for an existing
   * resource.
   *
   */
  def allocated[G[x] >: F[x], B >: A](implicit F: Bracket[G, Throwable]): G[(B, G[Unit])] = {
    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue(current: Resource[G, Any], stack: List[Any => Resource[G, Any]], release: G[Unit]): G[(Any, G[Unit])] =
      loop(current, stack, release)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop(current: Resource[G, Any],
                      stack: List[Any => Resource[G, Any]],
                      release: G[Unit]): G[(Any, G[Unit])] =
      current match {
        case a: Allocate[G, Any] =>
          F.bracketCase(a.resource) {
            case (a, rel) =>
              stack match {
                case Nil => F.pure(a -> F.guarantee(rel(ExitCase.Completed))(release))
                case l   => continue(l.head(a), l.tail, F.guarantee(rel(ExitCase.Completed))(release))
              }
          } {
            case (_, ExitCase.Completed) =>
              F.unit
            case ((_, release), ec) =>
              release(ec)
          }
        case b: Bind[G, _, Any] =>
          loop(b.source, b.fs.asInstanceOf[Any => Resource[G, Any]] :: stack, release)
        case s: Suspend[G, Any] =>
          s.resource.flatMap(continue(_, stack, release))
      }

    loop(this.asInstanceOf[Resource[F, Any]], Nil, F.unit).map {
      case (a, release) =>
        (a.asInstanceOf[A], release)
    }
  }

  /**
   * Applies an effectful transformation to the allocated resource. Like a
   * `flatMap` on `F[A]` while maintaining the resource context
   */
  def evalMap[G[x] >: F[x], B](f: A => G[B])(implicit F: Applicative[G]): Resource[G, B] =
    this.flatMap(a => Resource.liftF(f(a)))

  /**
   * Applies an effectful transformation to the allocated resource. Like a
   * `flatTap` on `F[A]` while maintaining the resource context
   */
  def evalTap[G[x] >: F[x], B](f: A => G[B])(implicit F: Applicative[G]): Resource[G, A] =
    this.evalMap(a => f(a).as(a))
}

object Resource extends ResourceInstances with ResourcePlatform {

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
  def apply[F[_], A](resource: F[(A, F[Unit])])(implicit F: Functor[F]): Resource[F, A] =
    Allocate[F, A] {
      resource.map {
        case (a, release) =>
          (a, (_: ExitCase[Throwable]) => release)
      }
    }

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
  def applyCase[F[_], A](resource: F[(A, ExitCase[Throwable] => F[Unit])]): Resource[F, A] =
    Allocate(resource)

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
  def make[F[_], A](acquire: F[A])(release: A => F[Unit])(implicit F: Functor[F]): Resource[F, A] =
    apply[F, A](acquire.map(a => a -> release(a)))

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
  def makeCase[F[_], A](
    acquire: F[A]
  )(release: (A, ExitCase[Throwable]) => F[Unit])(implicit F: Functor[F]): Resource[F, A] =
    applyCase[F, A](acquire.map(a => (a, (e: ExitCase[Throwable]) => release(a, e))))

  /**
   * Lifts a pure value into a resource. The resource has a no-op release.
   *
   * @param a the value to lift into a resource
   */
  def pure[F[_], A](a: A)(implicit F: Applicative[F]): Resource[F, A] =
    Allocate(F.pure((a, (_: ExitCase[Throwable]) => F.unit)))

  /**
   * Lifts an applicative into a resource. The resource has a no-op release.
   * Preserves interruptibility of `fa`.
   *
   * @param fa the value to lift into a resource
   */
  def liftF[F[_], A](fa: F[A])(implicit F: Applicative[F]): Resource[F, A] =
    Resource.suspend(fa.map(a => Resource.pure[F, A](a)))

  /**
   * Lifts an applicative into a resource as a `FunctionK`. The resource has a no-op release.
   */
  def liftK[F[_]](implicit F: Applicative[F]): F ~> Resource[F, *] =
    λ[F ~> Resource[F, *]](Resource.liftF(_))

  /**
   * Creates a [[Resource]] by wrapping a Java
   * [[https://docs.oracle.com/javase/8/docs/api/java/lang/AutoCloseable.html AutoCloseable]].
   *
   * Example:
   * {{{
   *   import cats.effect._
   *   import scala.io.Source
   *
   *   def reader[F[_]](data: String)(implicit F: Sync[F]): Resource[F, Source] =
   *     Resource.fromAutoCloseable(F.delay {
   *       Source.fromString(data)
   *     })
   * }}}
   * @param acquire The effect with the resource to acquire.
   * @param F the effect type in which the resource was acquired and will be released
   * @tparam F the type of the effect
   * @tparam A the type of the autocloseable resource
   * @return a Resource that will automatically close after use
   */
  def fromAutoCloseable[F[_], A <: AutoCloseable](acquire: F[A])(implicit F: Sync[F]): Resource[F, A] =
    Resource.make(acquire)(autoCloseable => F.delay(autoCloseable.close()))

  /**
   * Creates a [[Resource]] by wrapping a Java
   * [[https://docs.oracle.com/javase/8/docs/api/java/lang/AutoCloseable.html AutoCloseable]]
   * which is blocking in its adquire and close operations.
   *
   * Example:
   * {{{
   *   import java.io._
   *   import cats.effect._
   *
   *   def reader[F[_]](file: File, blocker: Blocker)(implicit F: Sync[F], cs: ContextShift[F]): Resource[F, BufferedReader] =
   *     Resource.fromAutoCloseableBlocking(blocker)(F.delay {
   *       new BufferedReader(new FileReader(file))
   *     })
   * }}}
   * @param acquire The effect with the resource to acquire
   * @param blocker The blocking context that will be used to compute acquire and close
   * @tparam F the type of the effect
   * @tparam A the type of the autocloseable resource
   * @return a Resource that will automatically close after use
   */
  def fromAutoCloseableBlocking[F[_]: Sync: ContextShift, A <: AutoCloseable](
    blocker: Blocker
  )(acquire: F[A]): Resource[F, A] =
    Resource.make(blocker.blockOn(acquire))(autoCloseable => blocker.delay(autoCloseable.close()))

  /**
   * Implementation for the `tailRecM` operation, as described via
   * the `cats.Monad` type class.
   */
  def tailRecM[F[_], A, B](a: A)(f: A => Resource[F, Either[A, B]])(implicit F: Monad[F]): Resource[F, B] = {
    def continue(r: Resource[F, Either[A, B]]): Resource[F, B] =
      r match {
        case a: Allocate[F, Either[A, B]] =>
          Suspend(a.resource.flatMap[Resource[F, B]] {
            case (Left(a), release) =>
              release(Completed).map(_ => tailRecM[F, A, B](a)(f))
            case (Right(b), release) =>
              F.pure(Allocate[F, B](F.pure((b, release))))
          })
        case s: Suspend[F, Either[A, B]] =>
          Suspend(s.resource.map(continue))
        case b: Bind[F, _, Either[A, B]] =>
          Bind(b.source, AndThen(b.fs).andThen(continue))
      }

    continue(f(a))
  }

  /**
   * `Resource` data constructor that wraps an effect allocating a resource,
   * along with its finalizers.
   */
  final case class Allocate[F[_], A](resource: F[(A, ExitCase[Throwable] => F[Unit])]) extends Resource[F, A]

  /**
   * `Resource` data constructor that encodes the `flatMap` operation.
   */
  final case class Bind[F[_], S, +A](source: Resource[F, S], fs: S => Resource[F, A]) extends Resource[F, A]

  /**
   * `Resource` data constructor that suspends the evaluation of another
   * resource value.
   */
  final case class Suspend[F[_], A](resource: F[Resource[F, A]]) extends Resource[F, A]

  /**
   * Newtype encoding for a `Resource` datatype that has a `cats.Applicative`
   * capable of doing parallel processing in `ap` and `map2`, needed
   * for implementing `cats.Parallel`.
   *
   * Helpers are provided for converting back and forth in `Par.apply`
   * for wrapping any `IO` value and `Par.unwrap` for unwrapping.
   *
   * The encoding is based on the "newtypes" project by
   * Alexander Konovalov, chosen because it's devoid of boxing issues and
   * a good choice until opaque types will land in Scala.
   * [[https://github.com/alexknvl/newtypes alexknvl/newtypes]].
   *
   */
  type Par[+F[_], +A] = Par.Type[F, A]

  object Par {
    type Base
    trait Tag extends Any
    type Type[+F[_], +A] <: Base with Tag

    def apply[F[_], A](fa: Resource[F, A]): Type[F, A] =
      fa.asInstanceOf[Type[F, A]]

    def unwrap[F[_], A](fa: Type[F, A]): Resource[F, A] =
      fa.asInstanceOf[Resource[F, A]]
  }
}

abstract private[effect] class ResourceInstances extends ResourceInstances0 {
  implicit def catsEffectMonadErrorForResource[F[_], E](implicit F0: MonadError[F, E]): MonadError[Resource[F, *], E] =
    new ResourceMonadError[F, E] {
      def F = F0
    }

  implicit def catsEffectMonoidForResource[F[_], A](implicit F0: Monad[F], A0: Monoid[A]): Monoid[Resource[F, A]] =
    new ResourceMonoid[F, A] {
      def A = A0
      def F = F0
    }

  implicit def catsEffectLiftIOForResource[F[_]](implicit F00: LiftIO[F], F10: Applicative[F]): LiftIO[Resource[F, *]] =
    new ResourceLiftIO[F] {
      def F0 = F00
      def F1 = F10
    }

  implicit def catsEffectCommutativeApplicativeForResourcePar[F[_]](
    implicit F: Sync[F],
    P: Parallel[F]
  ): CommutativeApplicative[Resource.Par[F, *]] =
    new ResourceParCommutativeApplicative[F] {
      def F0 = F
      def F1 = P
    }

  implicit def catsEffectParallelForResource[F0[_]: Sync: Parallel]
    : Parallel.Aux[Resource[F0, *], Resource.Par[F0, *]] =
    new ResourceParallel[F0] {
      def F0 = catsEffectCommutativeApplicativeForResourcePar
      def F1 = catsEffectMonadForResource
    }
}

abstract private[effect] class ResourceInstances0 {
  implicit def catsEffectMonadForResource[F[_]](implicit F0: Monad[F]): Monad[Resource[F, *]] =
    new ResourceMonad[F] {
      def F = F0
    }

  implicit def catsEffectSemigroupForResource[F[_], A](implicit F0: Monad[F],
                                                       A0: Semigroup[A]): ResourceSemigroup[F, A] =
    new ResourceSemigroup[F, A] {
      def A = A0
      def F = F0
    }

  implicit def catsEffectSemigroupKForResource[F[_], A](implicit F0: Monad[F],
                                                        K0: SemigroupK[F]): ResourceSemigroupK[F] =
    new ResourceSemigroupK[F] {
      def F = F0
      def K = K0
    }
}

abstract private[effect] class ResourceMonadError[F[_], E] extends ResourceMonad[F] with MonadError[Resource[F, *], E] {
  import Resource.{Allocate, Bind, Suspend}

  implicit protected def F: MonadError[F, E]

  override def attempt[A](fa: Resource[F, A]): Resource[F, Either[E, A]] =
    fa match {
      case Allocate(fa) =>
        Allocate[F, Either[E, A]](F.attempt(fa).map {
          case Left(error)         => (Left(error), (_: ExitCase[Throwable]) => F.unit)
          case Right((a, release)) => (Right(a), release)
        })
      case Bind(source: Resource[F, Any], fs: (Any => Resource[F, A])) =>
        Suspend(F.pure(source).map[Resource[F, Either[E, A]]] { source =>
          Bind(attempt(source),
               (r: Either[E, Any]) =>
                 r match {
                   case Left(error) => Resource.pure[F, Either[E, A]](Left(error))
                   case Right(s)    => attempt(fs(s))
                 })
        })
      case Suspend(resource) =>
        Suspend(resource.attempt.map {
          case Left(error)               => Resource.pure[F, Either[E, A]](Left(error))
          case Right(fa: Resource[F, A]) => attempt(fa)
        })
    }

  def handleErrorWith[A](fa: Resource[F, A])(f: E => Resource[F, A]): Resource[F, A] =
    flatMap(attempt(fa)) {
      case Right(a) => Resource.pure[F, A](a)
      case Left(e)  => f(e)
    }

  def raiseError[A](e: E): Resource[F, A] =
    Resource.applyCase[F, A](F.raiseError(e))
}

abstract private[effect] class ResourceMonad[F[_]] extends Monad[Resource[F, *]] {
  implicit protected def F: Monad[F]

  override def map[A, B](fa: Resource[F, A])(f: A => B): Resource[F, B] =
    fa.map(f)

  def pure[A](a: A): Resource[F, A] =
    Resource.applyCase[F, A](F.pure((a, _ => F.unit)))

  def flatMap[A, B](fa: Resource[F, A])(f: A => Resource[F, B]): Resource[F, B] =
    fa.flatMap(f)

  def tailRecM[A, B](a: A)(f: A => Resource[F, Either[A, B]]): Resource[F, B] =
    Resource.tailRecM(a)(f)
}

abstract private[effect] class ResourceMonoid[F[_], A] extends ResourceSemigroup[F, A] with Monoid[Resource[F, A]] {
  implicit protected def A: Monoid[A]

  def empty: Resource[F, A] = Resource.pure[F, A](A.empty)
}

abstract private[effect] class ResourceSemigroup[F[_], A] extends Semigroup[Resource[F, A]] {
  implicit protected def F: Monad[F]
  implicit protected def A: Semigroup[A]

  def combine(rx: Resource[F, A], ry: Resource[F, A]): Resource[F, A] =
    for {
      x <- rx
      y <- ry
    } yield A.combine(x, y)
}

abstract private[effect] class ResourceSemigroupK[F[_]] extends SemigroupK[Resource[F, *]] {
  implicit protected def F: Monad[F]
  implicit protected def K: SemigroupK[F]

  def combineK[A](rx: Resource[F, A], ry: Resource[F, A]): Resource[F, A] =
    for {
      x <- rx
      y <- ry
      xy <- Resource.liftF(K.combineK(x.pure[F], y.pure[F]))
    } yield xy
}

abstract private[effect] class ResourceLiftIO[F[_]] extends LiftIO[Resource[F, *]] {
  implicit protected def F0: LiftIO[F]
  implicit protected def F1: Applicative[F]

  def liftIO[A](ioa: IO[A]): Resource[F, A] =
    Resource.liftF(F0.liftIO(ioa))
}

abstract private[effect] class ResourceParCommutativeApplicative[F[_]]
    extends CommutativeApplicative[Resource.Par[F, *]] {
  import Resource.Par
  import Resource.Par.{unwrap, apply => par}

  implicit protected def F0: Sync[F]
  implicit protected def F1: Parallel[F]

  final override def map[A, B](fa: Par[F, A])(f: A => B): Par[F, B] =
    par(unwrap(fa).map(f))
  final override def pure[A](x: A): Par[F, A] =
    par(Resource.pure[F, A](x))
  final override def product[A, B](fa: Par[F, A], fb: Par[F, B]): Par[F, (A, B)] =
    par(unwrap(fa).parZip(unwrap(fb)))
  final override def map2[A, B, Z](fa: Par[F, A], fb: Par[F, B])(f: (A, B) => Z): Par[F, Z] =
    map(product(fa, fb)) { case (a, b) => f(a, b) }
  final override def ap[A, B](ff: Par[F, A => B])(fa: Par[F, A]): Par[F, B] =
    map(product(ff, fa)) { case (ff, a) => ff(a) }
}

abstract private[effect] class ResourceParallel[F0[_]] extends Parallel[Resource[F0, *]] {
  protected def F0: Applicative[Resource.Par[F0, *]]
  protected def F1: Monad[Resource[F0, *]]

  type F[x] = Resource.Par[F0, x]

  final override val applicative: Applicative[Resource.Par[F0, *]] = F0
  final override val monad: Monad[Resource[F0, *]] = F1

  final override val sequential: Resource.Par[F0, *] ~> Resource[F0, *] =
    λ[Resource.Par[F0, *] ~> Resource[F0, *]](Resource.Par.unwrap(_))

  final override val parallel: Resource[F0, *] ~> Resource.Par[F0, *] =
    λ[Resource[F0, *] ~> Resource.Par[F0, *]](Resource.Par(_))
}
