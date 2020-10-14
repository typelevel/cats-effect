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

package cats.effect.kernel

import cats._
import cats.data.AndThen
import cats.arrow.FunctionK
import cats.syntax.all._
import cats.effect.kernel.implicits._

import scala.annotation.tailrec
import Resource.ExitCase

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
 *
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
 * Further node types are used internally. To compile a resource down to the
 * above three types, call [[Resource#preinterpret]].
 *
 * @tparam F the effect type in which the resource is allocated and released
 * @tparam A the type of resource
 */
sealed abstract class Resource[+F[_], +A] {
  private[effect] type F0[x] <: F[x]

  import Resource.{Allocate, Bind, LiftF, MapK, OnFinalizeCase, Pure, Suspend}

  private[effect] def fold[G[x] >: F[x], B](
      onOutput: A => G[B],
      onRelease: G[Unit] => G[Unit]
  )(implicit G: Resource.Bracket[G]): G[B] = {
    sealed trait Stack[AA]
    case object Nil extends Stack[A]
    final case class Frame[AA, BB](head: AA => Resource[G, BB], tail: Stack[BB])
        extends Stack[AA]

    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue[C](current: Resource[G, C], stack: Stack[C]): G[B] =
      loop(current, stack)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop[C](current: Resource[G, C], stack: Stack[C]): G[B] =
      current.preinterpret match {
        case Allocate(resource) =>
          G.bracketCase(resource) {
            case (a, _) =>
              stack match {
                case Nil => onOutput(a)
                case Frame(head, tail) => continue(head(a), tail)
              }
          } {
            case ((_, release), ec) =>
              onRelease(release(ec))
          }
        case Bind(source, fs) =>
          loop(source, Frame(fs, stack))
        case Suspend(resource) =>
          G.flatMap(resource)(continue(_, stack))
      }
    loop(this, Nil)
  }

  /**
   * Compiles this down to the three primitive types:
   *
   *  - [[cats.effect.Resource.Allocate Allocate]]
   *  - [[cats.effect.Resource.Suspend Suspend]]
   *  - [[cats.effect.Resource.Bind Bind]]
   *
   * Note that this is done in a "shallow" fashion - when traversing a [[Resource]]
   * recursively, this will need to be done repeatedly.
   */
  def preinterpret[G[x] >: F[x]](implicit G: Applicative[G]): Resource.Primitive[G, A] = {
    @tailrec def loop(current: Resource[G, A]): Resource.Primitive[G, A] =
      current.invariant.widen[G] match {
        case pr: Resource.Primitive[G, A] => pr
        case Pure(a) => Allocate((a, (_: ExitCase) => G.unit).pure[G])
        case LiftF(fa) =>
          Suspend(fa.map[Resource[G, A]](a => Allocate((a, (_: ExitCase) => G.unit).pure[G])))
        case OnFinalizeCase(resource, finalizer) =>
          Bind(
            Allocate[G, Unit](G.pure[(Unit, ExitCase => G[Unit])](() -> finalizer)),
            (_: Unit) => resource)
        case MapK(rea, fk0) =>
          // this would be easier if we could call `rea.preinterpret` but we don't have
          // the right `Applicative` instance available.
          val fk = fk0.asInstanceOf[rea.F0 ~> G] // scala 2 infers this as `Any ~> G`
          rea.invariant match {
            case Allocate(resource) =>
              Allocate(fk(resource).map {
                case (a, r) => (a, r.andThen(fk(_)))
              })
            case Bind(source, f0) =>
              Bind(source.mapK(fk), f0.andThen(_.mapK(fk)))
            case Suspend(resource) =>
              Suspend(fk(resource).map(_.mapK(fk)))
            case OnFinalizeCase(resource, finalizer) =>
              Bind(
                Allocate[G, Unit](
                  G.pure[(Unit, ExitCase => G[Unit])](() -> finalizer.andThen(fk.apply))),
                (_: Unit) => resource.mapK(fk))
            case Pure(a) => Allocate((a, (_: ExitCase) => G.unit).pure[G])
            case LiftF(rea) =>
              Suspend(fk(rea).map[Resource[G, A]](a =>
                Allocate((a, (_: ExitCase) => G.unit).pure[G])))
            case MapK(ea0, ek) =>
              loop(ea0.invariant.mapK {
                new FunctionK[ea0.F0, G] {
                  def apply[A0](fa: ea0.F0[A0]): G[A0] = fk(ek(fa))
                }
              })
          }
      }
    loop(this)
  }

  /**
   * Allocates a resource and supplies it to the given function.
   * The resource is released as soon as the resulting `F[B]` is
   * completed, whether normally or as a raised error.
   *
   * @param f the function to apply to the allocated resource
   * @return the result of applying [F] to
   */
  def use[G[x] >: F[x], B](f: A => G[B])(implicit G: Resource.Bracket[G]): G[B] =
    fold[G, B](f, identity)

  /**
   * Allocates a resource with a non-terminating use action.
   * Useful to run programs that are expressed entirely in `Resource`.
   *
   * The finalisers run when the resulting program fails or gets interrupted.
   */
  def useForever[G[x] >: F[x]](implicit G: Spawn[G]): G[Nothing] =
    use[G, Nothing](_ => G.never)

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
   */
  def parZip[G[x] >: F[x]: Concurrent, B](
      that: Resource[G, B]
  ): Resource[G, (A, B)] = {
    type Update = (G[Unit] => G[Unit]) => G[Unit]

    def allocate[C](r: Resource[G, C], storeFinalizer: Update): G[C] =
      r.fold[G, C](
        _.pure[G],
        release => storeFinalizer(Resource.Bracket[G].guarantee(_)(release))
      )

    val bothFinalizers = Ref.of(().pure[G] -> ().pure[G])

    Resource.make(bothFinalizers)(_.get.flatMap(_.parTupled).void).evalMap { store =>
      val leftStore: Update = f => store.update(_.leftMap(f))
      val rightStore: Update =
        f =>
          store.update(t => (t._1, f(t._2))) // _.map(f) doesn't work on 0.25.0 for some reason

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
  def map[B](f: A => B): Resource[F, B] =
    flatMap(a => Resource.pure[F, B](f(a)))

  /**
   * Given a natural transformation from `F` to `G`, transforms this
   * Resource from effect `F` to effect `G`.
   */
  def mapK[G[x] >: F[x], H[_]](
      f: G ~> H
  ): Resource[H, A] = Resource.MapK(this, f)

  /**
   * Runs `finalizer` when this resource is closed. Unlike the release action passed to `Resource.make`, this will
   * run even if resource acquisition fails or is canceled.
   */
  def onFinalize[G[x] >: F[x]](finalizer: G[Unit]): Resource[G, A] =
    onFinalizeCase(_ => finalizer)

  /**
   * Like `onFinalize`, but the action performed depends on the exit case.
   */
  def onFinalizeCase[G[x] >: F[x]](f: ExitCase => G[Unit]): Resource[G, A] =
    OnFinalizeCase(this, f)

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
   */
  def allocated[G[x] >: F[x], B >: A](implicit G: Resource.Bracket[G]): G[(B, G[Unit])] = {
    sealed trait Stack[AA]
    case object Nil extends Stack[B]
    final case class Frame[AA, BB](head: AA => Resource[G, BB], tail: Stack[BB])
        extends Stack[AA]

    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue[C](
        current: Resource[G, C],
        stack: Stack[C],
        release: G[Unit]): G[(B, G[Unit])] =
      loop(current, stack, release)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop[C](
        current: Resource[G, C],
        stack: Stack[C],
        release: G[Unit]): G[(B, G[Unit])] =
      current.preinterpret match {
        case Allocate(resource) =>
          G.bracketCase(resource) {
            case (a, rel) =>
              stack match {
                case Nil =>
                  G.pure((a: B) -> G.guarantee(rel(ExitCase.Succeeded))(release))
                case Frame(head, tail) =>
                  continue(head(a), tail, G.guarantee(rel(ExitCase.Succeeded))(release))
              }
          } {
            case (_, ExitCase.Succeeded) =>
              G.unit
            case ((_, release), ec) =>
              release(ec)
          }
        case Bind(source, fs) =>
          loop(source, Frame(fs, stack), release)
        case Suspend(resource) =>
          resource.flatMap(continue(_, stack, release))
      }

    loop(this, Nil, G.unit)
  }

  /**
   * Applies an effectful transformation to the allocated resource. Like a
   * `flatMap` on `F[A]` while maintaining the resource context
   */
  def evalMap[G[x] >: F[x], B](f: A => G[B]): Resource[G, B] =
    this.flatMap(a => Resource.liftF(f(a)))

  /**
   * Applies an effectful transformation to the allocated resource. Like a
   * `flatTap` on `F[A]` while maintaining the resource context
   */
  def evalTap[G[x] >: F[x], B](f: A => G[B]): Resource[G, A] =
    this.flatMap(a => Resource.liftF(f(a)).map(_ => a))

  /**
   * Converts this to an `InvariantResource` to facilitate pattern matches
   * that Scala 2 cannot otherwise handle correctly.
   */

  private[effect] def invariant: Resource.InvariantResource[F0, A]
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
          (a, (_: ExitCase) => release)
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
  def applyCase[F[_], A](resource: F[(A, ExitCase => F[Unit])]): Resource[F, A] =
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
  def make[F[_], A](acquire: F[A])(release: A => F[Unit])(
      implicit F: Functor[F]): Resource[F, A] =
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
  )(release: (A, ExitCase) => F[Unit])(implicit F: Functor[F]): Resource[F, A] =
    applyCase[F, A](acquire.map(a => (a, e => release(a, e))))

  /**
   * Lifts a pure value into a resource. The resource has a no-op release.
   *
   * @param a the value to lift into a resource
   */
  def pure[F[_], A](a: A): Resource[F, A] =
    Pure(a)

  /**
   * Lifts an applicative into a resource. The resource has a no-op release.
   * Preserves interruptibility of `fa`.
   *
   * @param fa the value to lift into a resource
   */
  def liftF[F[_], A](fa: F[A]): Resource[F, A] =
    Resource.LiftF(fa)

  /**
   * Lifts an applicative into a resource as a `FunctionK`. The resource has a no-op release.
   */
  def liftK[F[_]]: F ~> Resource[F, *] =
    new (F ~> Resource[F, *]) {
      def apply[A](fa: F[A]): Resource[F, A] = Resource.liftF(fa)
    }

  /**
   * Like `Resource`, but invariant in `F`. Facilitates pattern matches that Scala 2 cannot
   * otherwise handle correctly.
   */
  private[effect] sealed trait InvariantResource[F[_], +A] extends Resource[F, A] {
    type F0[x] = F[x]
    override private[effect] def invariant: InvariantResource[F0, A] = this

    /**
     * Widens the effect type of this `InvariantResource` from `F` to `G`.
     * The `Functor` requirement makes this a type-safe operation.
     */
    def widen[G[x] >: F[x]: Functor] = this.asInstanceOf[InvariantResource[G, A]]
  }

  /**
   * Creates a [[Resource]] by wrapping a Java
   * [[https://docs.oracle.com/javase/8/docs/api/java/lang/AutoCloseable.html AutoCloseable]].
   *
   * In most real world cases, implementors of AutoCloseable are
   * blocking as well, so the close action runs in the blocking
   * context.
   *
   * Example:
   * {{{
   *   import cats.effect._
   *   import scala.io.Source
   *
   *   def reader[F[_]](data: String)(implicit F: Sync[F]): Resource[F, Source] =
   *     Resource.fromAutoCloseable(F.blocking {
   *       Source.fromString(data)
   *     })
   * }}}
   * @param acquire The effect with the resource to acquire.
   * @param F the effect type in which the resource was acquired and will be released
   * @tparam F the type of the effect
   * @tparam A the type of the autocloseable resource
   * @return a Resource that will automatically close after use
   */
  def fromAutoCloseable[F[_], A <: AutoCloseable](acquire: F[A])(
      implicit F: Sync[F]): Resource[F, A] =
    Resource.make(acquire)(autoCloseable => F.blocking(autoCloseable.close()))

  /**
   * Public supertype for the three node types that constitute teh public API
   * for interpreting a [[Resource]].
   */
  sealed trait Primitive[F[_], +A] extends InvariantResource[F, A]

  /**
   * `Resource` data constructor that wraps an effect allocating a resource,
   * along with its finalizers.
   */
  final case class Allocate[F[_], A](resource: F[(A, ExitCase => F[Unit])])
      extends Primitive[F, A]

  /**
   * `Resource` data constructor that encodes the `flatMap` operation.
   */
  final case class Bind[F[_], S, +A](source: Resource[F, S], fs: S => Resource[F, A])
      extends Primitive[F, A]

  /**
   * `Resource` data constructor that suspends the evaluation of another
   * resource value.
   */
  final case class Suspend[F[_], A](resource: F[Resource[F, A]]) extends Primitive[F, A]

  private[effect] final case class OnFinalizeCase[F[_], A](
      resource: Resource[F, A],
      finalizer: ExitCase => F[Unit])
      extends InvariantResource[F, A]

  private[effect] final case class Pure[F[_], +A](a: A) extends InvariantResource[F, A]

  private[effect] final case class LiftF[F[_], A](fa: F[A]) extends InvariantResource[F, A]

  private[effect] final case class MapK[E[_], F[_], A](source: Resource[E, A], f: E ~> F)
      extends InvariantResource[F, A]

  /**
   * Type for signaling the exit condition of an effectful
   * computation, that may either succeed, fail with an error or
   * get canceled.
   *
   * The types of exit signals are:
   *
   *  - [[ExitCase$.Succeeded Succeeded]]: for successful completion
   *  - [[ExitCase$.Error Error]]: for termination in failure
   *  - [[ExitCase$.Canceled Canceled]]: for abortion
   */
  sealed trait ExitCase extends Product with Serializable
  object ExitCase {

    /**
     * An [[ExitCase]] that signals successful completion.
     *
     * Note that "successful" is from the type of view of the
     * `MonadError` type that's implementing [[Bracket]].
     * When combining such a type with `EitherT` or `OptionT` for
     * example, this exit condition might not signal a successful
     * outcome for the user, but it does for the purposes of the
     * `bracket` operation. <-- TODO still true?
     */
    case object Succeeded extends ExitCase

    /**
     * An [[ExitCase]] signaling completion in failure.
     */
    final case class Errored(e: Throwable) extends ExitCase

    /**
     * An [[ExitCase]] signaling that the action was aborted.
     *
     * As an example this can happen when we have a cancelable data type,
     * like [[IO]] and the task yielded by `bracket` gets canceled
     * when it's at its `use` phase.
     *
     * Thus [[Bracket]] allows you to observe interruption conditions
     * and act on them.
     */
    case object Canceled extends ExitCase
  }

  @annotation.implicitNotFound(
    "Cannot find an instance for Resource.Bracket. This normally means you need to add implicit evidence of MonadCancel[${F}, Throwable]")
  trait Bracket[F[_]] extends MonadThrow[F] {
    def bracketCase[A, B](acquire: F[A])(use: A => F[B])(
        release: (A, ExitCase) => F[Unit]): F[B]

    def bracket[A, B](acquire: F[A])(use: A => F[B])(release: A => F[Unit]): F[B] =
      bracketCase(acquire)(use)((a, _) => release(a))

    def guarantee[A](fa: F[A])(finalizer: F[Unit]): F[A] =
      bracket(unit)(_ => fa)(_ => finalizer)

    def guaranteeCase[A](fa: F[A])(finalizer: ExitCase => F[Unit]): F[A] =
      bracketCase(unit)(_ => fa)((_, e) => finalizer(e))
  }

  trait Bracket0 {
    implicit def catsEffectResourceBracketForSync[F[_]](implicit F0: Sync[F]): Bracket[F] =
      new SyncBracket[F] {
        implicit protected def F: Sync[F] = F0
      }

    trait SyncBracket[F[_]] extends Bracket[F] {
      implicit protected def F: Sync[F]

      def bracketCase[A, B](acquire: F[A])(use: A => F[B])(
          release: (A, ExitCase) => F[Unit]): F[B] =
        flatMap(acquire) { a =>
          val handled = onError(use(a)) {
            case e => void(attempt(release(a, ExitCase.Errored(e))))
          }
          flatMap(handled)(b => as(attempt(release(a, ExitCase.Succeeded)), b))
        }

      def pure[A](x: A): F[A] = F.pure(x)
      def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = F.handleErrorWith(fa)(f)
      def raiseError[A](e: Throwable): F[A] = F.raiseError(e)
      def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = F.flatMap(fa)(f)
      def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = F.tailRecM(a)(f)
    }
  }

  object Bracket extends Bracket0 {
    def apply[F[_]](implicit F: Bracket[F]): F.type = F

    implicit def bracketMonadCancel[F[_]](
        implicit F0: MonadCancel[F, Throwable]
    ): Bracket[F] =
      new MonadCancelBracket[F] {
        implicit protected def F: MonadCancel[F, Throwable] = F0
      }

    trait MonadCancelBracket[F[_]] extends Bracket[F] {
      implicit protected def F: MonadCancel[F, Throwable]

      def bracketCase[A, B](acquire: F[A])(use: A => F[B])(
          release: (A, ExitCase) => F[Unit]): F[B] =
        F.uncancelable { poll =>
          flatMap(acquire) { a =>
            val finalized = F.onCancel(poll(use(a)), release(a, ExitCase.Canceled))
            val handled = onError(finalized) {
              case e => void(attempt(release(a, ExitCase.Errored(e))))
            }
            flatMap(handled)(b => as(attempt(release(a, ExitCase.Succeeded)), b))
          }
        }
      def pure[A](x: A): F[A] = F.pure(x)
      def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = F.handleErrorWith(fa)(f)
      def raiseError[A](e: Throwable): F[A] = F.raiseError(e)
      def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = F.flatMap(fa)(f)
      def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = F.tailRecM(a)(f)
    }
  }

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
  implicit def catsEffectMonadErrorForResource[F[_], E](
      implicit F0: MonadError[F, E]): MonadError[Resource[F, *], E] =
    new ResourceMonadError[F, E] {
      def F = F0
    }

  implicit def catsEffectMonoidForResource[F[_], A](
      implicit F0: Monad[F],
      A0: Monoid[A]): Monoid[Resource[F, A]] =
    new ResourceMonoid[F, A] {
      def A = A0
      def F = F0
    }

  implicit def catsEffectCommutativeApplicativeForResourcePar[F[_]](
      implicit F: Async[F]
  ): CommutativeApplicative[Resource.Par[F, *]] =
    new ResourceParCommutativeApplicative[F] {
      def F0 = F
    }

  implicit def catsEffectParallelForResource[F0[_]: Async]
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

  implicit def catsEffectSemigroupForResource[F[_], A](
      implicit F0: Monad[F],
      A0: Semigroup[A]): ResourceSemigroup[F, A] =
    new ResourceSemigroup[F, A] {
      def A = A0
      def F = F0
    }

  implicit def catsEffectSemigroupKForResource[F[_], A](
      implicit F0: Resource.Bracket[F],
      K0: SemigroupK[F],
      G0: Ref.Make[F]): ResourceSemigroupK[F] =
    new ResourceSemigroupK[F] {
      def F = F0
      def K = K0
      def G = G0
    }
}

abstract private[effect] class ResourceMonadError[F[_], E]
    extends ResourceMonad[F]
    with MonadError[Resource[F, *], E] {
  import Resource.{Allocate, Bind, Suspend}

  implicit protected def F: MonadError[F, E]

  override def attempt[A](fa: Resource[F, A]): Resource[F, Either[E, A]] =
    fa.preinterpret[F] match {
      case Allocate(fa) =>
        Allocate[F, Either[E, A]](F.attempt(fa).map {
          case Left(error) => (Left(error), (_: ExitCase) => F.unit)
          case Right((a, release)) => (Right(a), release)
        })
      case Bind(source: Resource[F, s], fs) =>
        Suspend(F.pure(source).map[Resource[F, Either[E, A]]] { source =>
          Bind(
            attempt(source),
            (r: Either[E, s]) =>
              r match {
                case Left(error) => Resource.pure[F, Either[E, A]](Left(error))
                case Right(s) => attempt(fs(s))
              })
        })
      case Suspend(resource) =>
        Suspend(F.attempt(resource) map {
          case Left(error) => Resource.pure[F, Either[E, A]](Left(error))
          case Right(fa: Resource[F, A]) => attempt(fa)
        })
    }

  def handleErrorWith[A](fa: Resource[F, A])(f: E => Resource[F, A]): Resource[F, A] =
    flatMap(attempt(fa)) {
      case Right(a) => Resource.pure[F, A](a)
      case Left(e) => f(e)
    }

  def raiseError[A](e: E): Resource[F, A] =
    Resource.applyCase[F, A](F.raiseError(e))
}

abstract private[effect] class ResourceMonad[F[_]] extends Monad[Resource[F, *]] {
  import Resource.{Allocate, Bind, Suspend}

  implicit protected def F: Monad[F]

  override def map[A, B](fa: Resource[F, A])(f: A => B): Resource[F, B] =
    fa.map(f)

  def pure[A](a: A): Resource[F, A] =
    Resource.pure(a)

  def flatMap[A, B](fa: Resource[F, A])(f: A => Resource[F, B]): Resource[F, B] =
    fa.flatMap(f)

  def tailRecM[A, B](a: A)(f: A => Resource[F, Either[A, B]]): Resource[F, B] = {
    def continue(r: Resource[F, Either[A, B]]): Resource[F, B] =
      r.preinterpret[F] match {
        case Allocate(resource) =>
          Suspend(F.flatMap(resource) {
            case (eab, release) =>
              (eab: Either[A, B]) match {
                case Left(a) =>
                  F.map(release(ExitCase.Succeeded))(_ => tailRecM(a)(f))
                case Right(b) =>
                  F.pure[Resource[F, B]](Allocate[F, B](F.pure((b, release))))
              }
          })
        case Suspend(resource) =>
          Suspend(F.map(resource)(continue))
        case b: Bind[F, s, Either[A, B]] =>
          Bind(b.source, AndThen(b.fs).andThen(continue))
      }

    continue(f(a))
  }
}

abstract private[effect] class ResourceMonoid[F[_], A]
    extends ResourceSemigroup[F, A]
    with Monoid[Resource[F, A]] {
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
  implicit protected def F: Resource.Bracket[F]
  implicit protected def K: SemigroupK[F]
  implicit protected def G: Ref.Make[F]

  def combineK[A](ra: Resource[F, A], rb: Resource[F, A]): Resource[F, A] =
    Resource.make(Ref[F].of(F.unit))(_.get.flatten).evalMap { finalizers =>
      def allocate(r: Resource[F, A]): F[A] =
        r.fold(
          _.pure[F],
          (release: F[Unit]) => finalizers.update(Resource.Bracket[F].guarantee(_)(release)))

      K.combineK(allocate(ra), allocate(rb))
    }
}

abstract private[effect] class ResourceParCommutativeApplicative[F[_]]
    extends CommutativeApplicative[Resource.Par[F, *]] {
  import Resource.Par
  import Resource.Par.{unwrap, apply => par}

  implicit protected def F0: Async[F]

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
    new (Resource.Par[F0, *] ~> Resource[F0, *]) {
      def apply[A](fa: Resource.Par[F0, A]): Resource[F0, A] = Resource.Par.unwrap(fa)
    }

  final override val parallel: Resource[F0, *] ~> Resource.Par[F0, *] =
    new (Resource[F0, *] ~> Resource.Par[F0, *]) {
      def apply[A](fa: Resource[F0, A]): Resource.Par[F0, A] = Resource.Par(fa)
    }
}
