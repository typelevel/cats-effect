/*
 * Copyright 2020-2024 Typelevel
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
import cats.data.Kleisli
import cats.effect.kernel.Resource.Pure
import cats.effect.kernel.implicits._
import cats.effect.kernel.instances.spawn
import cats.syntax.all._

import scala.annotation.tailrec
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}

/**
 * `Resource` is a data structure which encodes the idea of executing an action which has an
 * associated finalizer that needs to be run when the action completes.
 *
 * Examples include scarce resources like files, which need to be closed after use, or
 * concurrent abstractions like locks, which need to be released after having been acquired.
 *
 * There are several constructors to allocate a resource, the most common is
 * [[Resource.make make]]:
 *
 * {{{
 *   def open(file: File): Resource[IO, BufferedReader] = {
 *     val openFile = IO(new BufferedReader(new FileReader(file)))
 *     Resource.make(acquire = openFile)(release = f => IO(f.close))
 *   }
 * }}}
 *
 * and several methods to consume a resource, the most common is [[Resource!.use use]]:
 *
 * {{{
 *   def readFile(file: BufferedReader): IO[Content]
 *
 *   open(file1).use(readFile)
 * }}}
 *
 * Finalisation (in this case file closure) happens when the action passed to `use` terminates.
 * Therefore, the code above is _not_ equivalent to:
 *
 * {{{
 *   open(file1).use(IO.pure).flatMap(readFile)
 * }}}
 *
 * which will instead result in an error, since the file gets closed after `pure`, meaning that
 * `.readFile` will then fail.
 *
 * Also note that a _new_ resource is allocated every time `use` is called, so the following
 * code opens and closes the resource twice:
 *
 * {{{
 *   val file: Resource[IO, File]
 *   file.use(read) >> file.use(read)
 * }}}
 *
 * If you want sharing, pass the result of allocating the resource around, and call `use` once.
 * {{{
 *   file.use { file => read(file) >> read(file) }
 * }}}
 *
 * The acquire and release actions passed to `make` are not interruptible, and release will run
 * when the action passed to `use` succeeds, fails, or is interrupted. You can use
 * [[Resource.makeCase makeCase]] to specify a different release logic depending on each of the
 * three outcomes above.
 *
 * It is also possible to specify an interruptible acquire though [[Resource.makeFull makeFull]]
 * but be warned that this is an advanced concurrency operation, which requires some care.
 *
 * Resource usage nests:
 *
 * {{{
 *   open(file1).use { in1 =>
 *     open(file2).use { in2 =>
 *       readFiles(in1, in2)
 *     }
 *   }
 * }}}
 *
 * However, it is more idiomatic to compose multiple resources together before `use`, exploiting
 * the fact that `Resource` forms a `Monad`, and therefore that resources can be nested through
 * `flatMap`. Nested resources are released in reverse order of acquisition. Outer resources are
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
 * A `Resource` can also lift arbitrary actions that don't require finalisation through
 * [[Resource.eval eval]]. Actions passed to `eval` preserve their interruptibility.
 *
 * Finally, `Resource` partakes in other abstractions such as `MonadError`, `Parallel`, and
 * `Monoid`, so make sure to explore those instances as well as the other methods not covered
 * here.
 *
 * `Resource` is encoded as a data structure, an ADT, described by the following node types:
 *
 *   - [[Resource.Allocate Allocate]]
 *   - [[Resource.Bind Bind]]
 *   - [[Resource.Pure Pure]]
 *   - [[Resource.Eval Eval]]
 *
 * Normally users don't need to care about these node types, unless conversions from `Resource`
 * into something else is needed (e.g. conversion from `Resource` into a streaming data type),
 * in which case they can be interpreted through pattern matching.
 *
 * @tparam F
 *   the effect type in which the resource is allocated and released
 * @tparam A
 *   the type of resource
 */
sealed abstract class Resource[F[_], +A] extends Serializable {
  import Resource._

  private[effect] def fold[B](
      onOutput: A => F[B],
      onRelease: (ExitCase => F[Unit], ExitCase) => F[Unit]
  )(implicit F: MonadCancel[F, Throwable]): F[B] = {
    sealed trait Stack[AA]
    case object Nil extends Stack[A]
    final case class Frame[AA, BB](head: AA => Resource[F, BB], tail: Stack[BB])
        extends Stack[AA]

    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue[C](current: Resource[F, C], stack: Stack[C]): F[B] =
      loop(current, stack)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop[C](current: Resource[F, C], stack: Stack[C]): F[B] =
      current match {
        case Allocate(resource) =>
          F.bracketFull(resource) {
            case (a, _) =>
              stack match {
                case Nil => onOutput(a)
                case Frame(head, tail) => continue(head(a), tail)
              }
          } {
            case ((_, release), outcome) =>
              onRelease(release, ExitCase.fromOutcome(outcome))
          }
        case Bind(source, fs) =>
          loop(source, Frame(fs, stack))
        case Pure(v) =>
          stack match {
            case Nil => onOutput(v)
            case Frame(head, tail) =>
              loop(head(v), tail)
          }
        case Eval(fa) =>
          fa.flatMap(a => continue(Resource.pure(a), stack))
      }
    loop(this, Nil)
  }

  /**
   * Allocates a resource and supplies it to the given function. The resource is released as
   * soon as the resulting `F[B]` is completed, whether normally or as a raised error.
   *
   * @param f
   *   the function to apply to the allocated resource
   * @return
   *   the result of applying [F] to
   */
  def use[B](f: A => F[B])(implicit F: MonadCancel[F, Throwable]): F[B] =
    fold(f, _.apply(_))

  /**
   * For a resource that allocates an action (type `F[B]`), allocate that action, run it and
   * release it.
   */

  def useEval[B](implicit ev: A <:< F[B], F: MonadCancel[F, Throwable]): F[B] =
    use(ev)

  /**
   * Allocates a resource with a non-terminating use action. Useful to run programs that are
   * expressed entirely in `Resource`.
   *
   * The finalisers run when the resulting program fails or gets interrupted.
   */
  def useForever(implicit F: Spawn[F]): F[Nothing] =
    use[Nothing](_ => F.never)

  /**
   * Allocates a resource and closes it immediately.
   */
  def use_(implicit F: MonadCancel[F, Throwable]): F[Unit] = use(_ => F.unit)

  /**
   * Allocates the resource and uses it to run the given Kleisli.
   */
  def useKleisli[B >: A, C](usage: Kleisli[F, B, C])(
      implicit F: MonadCancel[F, Throwable]): F[C] =
    use(usage.run)

  /**
   * Creates a FunctionK that, when applied, will allocate the resource and use it to run the
   * given Kleisli.
   */
  def useKleisliK[B >: A](implicit F: MonadCancel[F, Throwable]): Kleisli[F, B, *] ~> F =
    new (Kleisli[F, B, *] ~> F) {
      def apply[C](fa: Kleisli[F, B, C]): F[C] = useKleisli(fa)
    }

  /**
   * Allocates two resources concurrently, and combines their results in a tuple.
   *
   * The finalizers for the two resources are also run concurrently with each other, but within
   * _each_ of the two resources, nested finalizers are run in the usual reverse order of
   * acquisition.
   *
   * The same [[Resource.ExitCase]] is propagated to every finalizer. If both resources acquired
   * successfully, the [[Resource.ExitCase]] is determined by the outcome of [[use]]. Otherwise,
   * it is determined by which resource failed or canceled first during acquisition.
   *
   * Note that `Resource` also comes with a `cats.Parallel` instance that offers more convenient
   * access to the same functionality as `both`, for example via `parMapN`:
   *
   * {{{
   * import scala.concurrent.duration._
   * import cats.effect.{IO, Resource}
   * import cats.effect.std.Random
   * import cats.syntax.all._
   *
   * def mkResource(name: String) = {
   *   val acquire = for {
   *     n <- Random.scalaUtilRandom[IO].flatMap(_.nextIntBounded(1000))
   *     _ <- IO.sleep(n.millis)
   *     _ <- IO.println(s"Acquiring $$name")
   *   } yield name
   *
   *   def release(name: String) =
   *     IO.println(s"Releasing $$name")
   *
   *   Resource.make(acquire)(release)
   * }
   *
   * val r = (mkResource("one"), mkResource("two"))
   *   .parMapN((s1, s2) => s"I have \$s1 and \$s2")
   *   .use(IO.println(_))
   * }}}
   */
  def both[B](
      that: Resource[F, B]
  )(implicit F: Concurrent[F]): Resource[F, (A, B)] = {
    type Finalizer = Resource.ExitCase => F[Unit]
    type Update = (Finalizer => Finalizer) => F[Unit]

    def allocate[C](r: Resource[F, C], storeFinalizer: Update): F[C] =
      r.fold(
        _.pure[F],
        (release, _) => storeFinalizer(fin => ec => F.unit >> fin(ec).guarantee(release(ec)))
      )

    val noop: Finalizer = _ => F.unit
    val bothFinalizers = F.ref((noop, noop))

    Resource
      .makeCase(bothFinalizers) { (finalizers, ec) =>
        finalizers.get.flatMap {
          case (thisFin, thatFin) =>
            F.void(F.both(thisFin(ec), thatFin(ec)))
        }
      }
      .evalMap { store =>
        val thisStore: Update = f => store.update(_.bimap(f, identity))
        val thatStore: Update = f => store.update(_.bimap(identity, f))

        F.both(allocate(this, thisStore), allocate(that, thatStore))
      }
  }

  /**
   * Races the evaluation of two resource allocations and returns the result of the winner,
   * except in the case of cancelation.
   */
  def race[B](
      that: Resource[F, B]
  )(implicit F: Concurrent[F]): Resource[F, Either[A, B]] =
    Resource.applyFull { poll =>
      def cancelLoser[C](f: Fiber[F, Throwable, (C, ExitCase => F[Unit])]): F[Unit] =
        f.cancel *>
          f.join
            .flatMap(
              _.fold(
                F.unit,
                _ => F.unit,
                _.flatMap(_._2.apply(ExitCase.Canceled))
              )
            )

      poll(F.racePair(this.allocatedCase, that.allocatedCase)).flatMap {
        case Left((oc, f)) =>
          oc match {
            case Outcome.Succeeded(fa) =>
              cancelLoser(f).start.flatMap { f =>
                fa.map {
                  case (a, fin) =>
                    (
                      Either.left[A, B](a),
                      fin(_: ExitCase).guarantee(f.join.flatMap(_.embedNever)))
                }
              }
            case Outcome.Errored(ea) =>
              F.raiseError[(Either[A, B], ExitCase => F[Unit])](ea).guarantee(cancelLoser(f))
            case Outcome.Canceled() =>
              f.cancel *> f.join flatMap {
                case Outcome.Succeeded(fb) =>
                  fb.map { case (b, fin) => (Either.right[A, B](b), fin) }
                case Outcome.Errored(eb) =>
                  F.raiseError[(Either[A, B], ExitCase => F[Unit])](eb)
                case Outcome.Canceled() =>
                  poll(F.canceled) *> F.never[(Either[A, B], ExitCase => F[Unit])]
              }
          }
        case Right((f, oc)) =>
          oc match {
            case Outcome.Succeeded(fb) =>
              cancelLoser(f).start.flatMap { f =>
                fb.map {
                  case (b, fin) =>
                    (
                      Either.right[A, B](b),
                      fin(_: ExitCase).guarantee(f.join.flatMap(_.embedNever)))
                }
              }
            case Outcome.Errored(eb) =>
              F.raiseError[(Either[A, B], ExitCase => F[Unit])](eb).guarantee(cancelLoser(f))
            case Outcome.Canceled() =>
              f.cancel *> f.join flatMap {
                case Outcome.Succeeded(fa) =>
                  fa.map { case (a, fin) => (Either.left[A, B](a), fin) }
                case Outcome.Errored(ea) =>
                  F.raiseError[(Either[A, B], ExitCase => F[Unit])](ea)
                case Outcome.Canceled() =>
                  poll(F.canceled) *> F.never[(Either[A, B], ExitCase => F[Unit])]
              }
          }
      }
    }

  /**
   * Implementation for the `flatMap` operation, as described via the `cats.Monad` type class.
   */
  def flatMap[B](f: A => Resource[F, B]): Resource[F, B] =
    Bind(this, f)

  /**
   * Given a mapping function, transforms the resource provided by this Resource.
   *
   * This is the standard `Functor.map`.
   */
  def map[B](f: A => B): Resource[F, B] =
    flatMap(a => Resource.pure[F, B](f(a)))

  /**
   * Given a natural transformation from `F` to `G`, transforms this Resource from effect `F` to
   * effect `G`. The F and G constraint can also be satisfied by requiring a MonadCancelThrow[F]
   * and MonadCancelThrow[G].
   */
  def mapK[G[_]](
      f: F ~> G
  )(implicit F: MonadCancel[F, _], G: MonadCancel[G, _]): Resource[G, A] =
    this match {
      case Allocate(resource) =>
        Resource.applyFull { (gpoll: Poll[G]) =>
          gpoll {
            f {
              F.uncancelable { (fpoll: Poll[F]) => resource(fpoll) }
            }
          }.map {
            case (a, release) =>
              a -> ((r: ExitCase) => f(release(r)))
          }
        }
      case Bind(source, f0) =>
        // we insert a bind to get stack safety
        suspend(G.unit >> source.mapK(f).pure[G]).flatMap(x => f0(x).mapK(f))
      case Pure(a) =>
        Resource.pure(a)
      case Eval(fea) => Resource.eval(f(fea))
    }

  /**
   * Runs `precede` before this resource is allocated.
   */
  def preAllocate(precede: F[Unit]): Resource[F, A] =
    Resource.eval(precede).flatMap(_ => this)

  /**
   * Runs `finalizer` when this resource is closed. Unlike the release action passed to
   * `Resource.make`, this will run even if resource acquisition fails or is canceled.
   */
  def onFinalize(finalizer: F[Unit])(implicit F: Applicative[F]): Resource[F, A] =
    onFinalizeCase(_ => finalizer)

  /**
   * Like `onFinalize`, but the action performed depends on the exit case.
   */
  def onFinalizeCase(f: ExitCase => F[Unit])(implicit F: Applicative[F]): Resource[F, A] =
    Resource.makeCase(F.unit)((_, ec) => f(ec)).flatMap(_ => this)

  /**
   * Given a `Resource`, possibly built by composing multiple `Resource`s monadically, returns
   * the acquired resource, as well as a cleanup function that takes an
   * [[Resource.ExitCase exit case]] and runs all the finalizers for releasing it.
   *
   * If the outer `F` fails or is interrupted, `allocated` guarantees that the finalizers will
   * be called. However, if the outer `F` succeeds, it's up to the user to ensure the returned
   * `ExitCode => F[Unit]` is called once `A` needs to be released. If the returned `ExitCode =>
   * F[Unit]` is not called, the finalizers will not be run.
   *
   * For this reason, this is an advanced and potentially unsafe api which can cause a resource
   * leak if not used correctly, please prefer [[use]] as the standard way of running a
   * `Resource` program.
   *
   * Use cases include interacting with side-effectful apis that expect separate acquire and
   * release actions (like the `before` and `after` methods of many test frameworks), or complex
   * library code that needs to modify or move the finalizer for an existing resource.
   */
  def allocatedCase[B >: A](
      implicit F: MonadCancel[F, Throwable]): F[(B, ExitCase => F[Unit])] = {
    sealed trait Stack[AA]
    case object Nil extends Stack[B]
    final case class Frame[AA, BB](head: AA => Resource[F, BB], tail: Stack[BB])
        extends Stack[AA]

    // Indirection for calling `loop` needed because `loop` must be @tailrec
    def continue[C](
        current: Resource[F, C],
        stack: Stack[C],
        release: ExitCase => F[Unit]): F[(B, ExitCase => F[Unit])] =
      loop(current, stack, release)

    // Interpreter that knows how to evaluate a Resource data structure;
    // Maintains its own stack for dealing with Bind chains
    @tailrec def loop[C](
        current: Resource[F, C],
        stack: Stack[C],
        release: ExitCase => F[Unit]): F[(B, ExitCase => F[Unit])] =
      current match {
        case Allocate(resource) =>
          F uncancelable { poll =>
            resource(poll) flatMap {
              case (b, rel) =>
                // Insert F.unit to emulate defer for stack-safety
                val rel2 = (ec: ExitCase) => rel(ec).guarantee(F.unit >> release(ec))

                stack match {
                  case Nil =>
                    /*
                     * We *don't* poll here because this case represents the "there are no flatMaps"
                     * scenario. If we poll in this scenario, then the following code will have a
                     * masking gap (where F = Resource):
                     *
                     * F.uncancelable(_(F.uncancelable(_ => foo)))
                     *
                     * In this case, the inner uncancelable has no trailing flatMap, so it will hit
                     * this case exactly. If we poll, we will create the masking gap and surface
                     * cancelation improperly.
                     */
                    F.pure((b, rel2))

                  case Frame(head, tail) =>
                    poll(continue(head(b), tail, rel2))
                      .onCancel(rel(ExitCase.Canceled))
                      .onError { case e => rel(ExitCase.Errored(e)).handleError(_ => ()) }
                }
            }
          }

        case Bind(source, fs) =>
          loop(source, Frame(fs, stack), release)

        case Pure(v) =>
          stack match {
            case Nil =>
              (v: B, release).pure[F]
            case Frame(head, tail) =>
              loop(head(v), tail, release)
          }

        case Eval(fa) =>
          fa.flatMap(a => continue(Resource.pure(a), stack, release))
      }

    loop(this, Nil, _ => F.unit)
  }

  /**
   * Given a `Resource`, possibly built by composing multiple `Resource`s monadically, returns
   * the acquired resource, as well as an action that runs all the finalizers for releasing it.
   *
   * If the outer `F` fails or is interrupted, `allocated` guarantees that the finalizers will
   * be called. However, if the outer `F` succeeds, it's up to the user to ensure the returned
   * `F[Unit]` is called once `A` needs to be released. If the returned `F[Unit]` is not called,
   * the finalizers will not be run.
   *
   * For this reason, this is an advanced and potentially unsafe api which can cause a resource
   * leak if not used correctly, please prefer [[use]] as the standard way of running a
   * `Resource` program.
   *
   * Use cases include interacting with side-effectful apis that expect separate acquire and
   * release actions (like the `before` and `after` methods of many test frameworks), or complex
   * library code that needs to modify or move the finalizer for an existing resource.
   */
  def allocated[B >: A](implicit F: MonadCancel[F, Throwable]): F[(B, F[Unit])] =
    F.uncancelable(poll =>
      poll(allocatedCase).map { case (b, fin) => (b, fin(ExitCase.Succeeded)) })

  /**
   * Applies an effectful transformation to the allocated resource. Like a `flatMap` on `F[A]`
   * while maintaining the resource context
   */
  def evalMap[B](f: A => F[B]): Resource[F, B] =
    this.flatMap(a => Resource.eval(f(a)))

  /**
   * Applies an effectful transformation to the allocated resource. Like a `flatTap` on `F[A]`
   * while maintaining the resource context
   */
  def evalTap[B](f: A => F[B]): Resource[F, A] =
    this.flatMap(a => Resource.eval(f(a)).map(_ => a))

  /**
   * Acquires the resource, runs `gb` and closes the resource once `gb` terminates, fails or
   * gets interrupted
   */
  def surround[B](gb: F[B])(implicit F: MonadCancel[F, Throwable]): F[B] =
    use(_ => gb)

  /**
   * Creates a FunctionK that can run `gb` within a resource, which is then closed once `gb`
   * terminates, fails or gets interrupted
   */
  def surroundK(implicit F: MonadCancel[F, Throwable]): F ~> F =
    new (F ~> F) {
      override def apply[B](gb: F[B]): F[B] = surround(gb)
    }

  def forceR[B](that: Resource[F, B])(implicit F: MonadCancel[F, Throwable]): Resource[F, B] =
    Resource.applyFull { poll => poll(this.use_ !> that.allocatedCase) }

  def !>[B](that: Resource[F, B])(implicit F: MonadCancel[F, Throwable]): Resource[F, B] =
    forceR(that)

  def onCancel(fin: Resource[F, Unit])(implicit F: MonadCancel[F, Throwable]): Resource[F, A] =
    Resource.applyFull { poll => poll(this.allocatedCase).onCancel(fin.use_) }

  def guaranteeCase(
      fin: Outcome[Resource[F, *], Throwable, A @uncheckedVariance] => Resource[F, Unit])(
      implicit F: MonadCancel[F, Throwable]): Resource[F, A] =
    Resource.applyFull { poll =>
      poll(this.allocatedCase).guaranteeCase {
        case Outcome.Succeeded(ft) =>
          fin(Outcome.Succeeded(Resource.eval(ft.map(_._1)))).use_.handleErrorWith { e =>
            ft.flatMap(_._2(ExitCase.Errored(e))).handleError(_ => ()) >> F.raiseError(e)
          }

        case Outcome.Errored(e) =>
          fin(Outcome.Errored(e)).use_.handleError(_ => ())

        case Outcome.Canceled() =>
          fin(Outcome.Canceled()).use_
      }
    }

  /*
   * 1. If the scope containing the *fiber* terminates:
   *   a) if the fiber is incomplete, run its inner finalizers when it completes
   *   b) if the fiber is succeeded, run its finalizers
   * 2. If the fiber is canceled or errored, finalize
   * 3. If the fiber succeeds and .cancel won the race, finalize eagerly and
   *    `join` results in `Canceled()`
   * 4. If the fiber succeeds and .cancel lost the race or wasn't called,
   *    finalize naturally when the containing scope ends, `join` returns
   *    the value
   */
  def start(
      implicit
      F: Concurrent[F]): Resource[F, Fiber[Resource[F, *], Throwable, A @uncheckedVariance]] = {
    final case class State(
        fin: F[Unit] = F.unit,
        finalizeOnComplete: Boolean = false,
        confirmedFinalizeOnComplete: Boolean = false)

    Resource {
      import Outcome._

      F.ref[State](State()) flatMap { state =>
        val finalized: F[A] = F uncancelable { poll =>
          poll(this.allocated) guarantee {
            // confirm that we completed and we were asked to clean up
            // note that this will run even if the inner effect short-circuited
            state update { s =>
              if (s.finalizeOnComplete)
                s.copy(confirmedFinalizeOnComplete = true)
              else
                s
            }
          } flatMap {
            // if the inner F has a zero, we lose the finalizers, but there's no avoiding that
            case (a, rel) =>
              val action = state modify { s =>
                if (s.confirmedFinalizeOnComplete)
                  (s, rel.handleError(_ => ()))
                else
                  (s.copy(fin = rel), F.unit)
              }

              action.flatten.as(a)
          }
        }

        F.start(finalized) map { outer =>
          val fiber = new Fiber[Resource[F, *], Throwable, A] {
            def cancel =
              Resource eval {
                F uncancelable { poll =>
                  // technically cancel is uncancelable, but separation of concerns and what not
                  poll(outer.cancel) *> state.update(_.copy(finalizeOnComplete = true))
                }
              }

            def join =
              Resource eval {
                outer.join.flatMap[Outcome[Resource[F, *], Throwable, A]] {
                  case Canceled() =>
                    Outcome.canceled[Resource[F, *], Throwable, A].pure[F]

                  case Errored(e) =>
                    Outcome.errored[Resource[F, *], Throwable, A](e).pure[F]

                  case Succeeded(fp) =>
                    state.get map { s =>
                      if (s.confirmedFinalizeOnComplete)
                        Outcome.canceled[Resource[F, *], Throwable, A]
                      else
                        Outcome.succeeded(Resource.eval(fp))
                    }
                }
              }
          }

          val finalizeOuter =
            state.modify(s => (s.copy(finalizeOnComplete = true), s.fin)).flatten

          (fiber, finalizeOuter)
        }
      }
    }
  }

  def evalOn(ec: ExecutionContext)(implicit F: Async[F]): Resource[F, A] =
    Resource.applyFull { poll =>
      poll(this.allocatedCase).evalOn(ec).map {
        case (a, release) => (a, release.andThen(_.evalOn(ec)))
      }
    }

  def attempt[E](implicit F: ApplicativeError[F, E]): Resource[F, Either[E, A]] =
    this match {
      case Allocate(resource) =>
        Resource.applyFull { poll =>
          resource(poll).attempt.map {
            case Left(error) => (Left(error), (_: ExitCase) => F.unit)
            case Right((a, release)) => (Right(a), release)
          }
        }
      case Bind(source, f) =>
        Resource.unit.flatMap(_ => source.attempt).flatMap {
          case Left(error) => Resource.pure(error.asLeft)
          case Right(s) => f(s).attempt
        }
      case p @ Pure(_) =>
        Resource.pure(p.a.asRight)
      case e @ Eval(_) =>
        Resource.eval(e.fa.attempt)
    }

  def handleErrorWith[B >: A, E](f: E => Resource[F, B])(
      implicit F: ApplicativeError[F, E]): Resource[F, B] =
    attempt.flatMap {
      case Right(a) => Resource.pure(a)
      case Left(e) => f(e)
    }

  def combine[B >: A](that: Resource[F, B])(implicit A: Semigroup[B]): Resource[F, B] =
    for {
      x <- this
      y <- that
    } yield A.combine(x, y)

  def combineK[B >: A](that: Resource[F, B])(
      implicit F: MonadCancel[F, Throwable],
      K: SemigroupK[F],
      G: Ref.Make[F]): Resource[F, B] =
    Resource
      .makeCase(Ref[F].of((_: Resource.ExitCase) => F.unit))((fin, ec) =>
        fin.get.flatMap(_(ec)))
      .evalMap { finalizers =>
        def allocate(r: Resource[F, B]): F[B] =
          r.fold(
            _.pure[F],
            (release, _) =>
              finalizers.update(fin => ec => F.unit >> fin(ec).guarantee(release(ec)))
          )

        K.combineK(allocate(this), allocate(that))
      }

}

object Resource extends ResourceFOInstances0 with ResourceHOInstances0 with ResourcePlatform {

  /**
   * Creates a resource from an allocating effect.
   *
   * @see
   *   [[make]] for a version that separates the needed resource with its finalizer tuple in two
   *   parameters
   *
   * @tparam F
   *   the effect type in which the resource is acquired and released
   * @tparam A
   *   the type of the resource
   * @param resource
   *   an effect that returns a tuple of a resource and an effect to release it
   */
  def apply[F[_], A](resource: F[(A, F[Unit])])(implicit F: Functor[F]): Resource[F, A] =
    applyCase[F, A] {
      resource.map {
        case (a, release) =>
          (a, (_: ExitCase) => release)
      }
    }

  /**
   * Creates a resource from an allocating effect, with a finalizer that is able to distinguish
   * between [[ExitCase exit cases]].
   *
   * @see
   *   [[makeCase]] for a version that separates the needed resource with its finalizer tuple in
   *   two parameters
   *
   * @tparam F
   *   the effect type in which the resource is acquired and released
   * @tparam A
   *   the type of the resource
   * @param resource
   *   an effect that returns a tuple of a resource and an effectful function to release it
   */
  def applyCase[F[_], A](resource: F[(A, ExitCase => F[Unit])]): Resource[F, A] =
    applyFull(_ => resource)

  /**
   * Creates a resource from an allocating effect, with a finalizer that is able to distinguish
   * between [[ExitCase exit cases]].
   *
   * The action takes a `Poll[F]` to allow for interruptible acquires, which is most often
   * useful when acquiring lock-like structure: it should be possible to interrupt a fiber
   * waiting on a lock, but if it does get acquired, release need to be guaranteed.
   *
   * Note that in this case the acquire action should know how to cleanup after itself in case
   * it gets canceled, since Resource will only guarantee release when acquire succeeds (and
   * when the actions in `use` or `flatMap` fail, succeed, or get canceled)
   *
   * TODO make sure this api, which is more general than makeFull, doesn't allow for
   * interruptible releases
   *
   * @see
   *   [[makeFull]] for a version that separates the needed resource with its finalizer tuple in
   *   two parameters
   *
   * @tparam F
   *   the effect type in which the resource is acquired and released
   * @tparam A
   *   the type of the resource
   * @param resource
   *   an effect that returns a tuple of a resource and an effectful function to release it,
   *   where acquisition can potentially be interrupted
   */
  def applyFull[F[_], A](resource: Poll[F] => F[(A, ExitCase => F[Unit])]): Resource[F, A] =
    Allocate(resource)

  /**
   * Given a `Resource` suspended in `F[_]`, lifts it in the `Resource` context.
   */
  def suspend[F[_], A](fr: F[Resource[F, A]]): Resource[F, A] =
    Resource.eval(fr).flatMap(x => x)

  /**
   * Creates a resource from an acquiring effect and a release function.
   *
   * @tparam F
   *   the effect type in which the resource is acquired and released
   * @tparam A
   *   the type of the resource
   * @param acquire
   *   an effect to acquire a resource
   * @param release
   *   a function to effectfully release the resource returned by `acquire`
   */
  def make[F[_], A](acquire: F[A])(release: A => F[Unit])(
      implicit F: Functor[F]): Resource[F, A] =
    apply[F, A](acquire.map(a => a -> release(a)))

  /**
   * Creates a resource from an acquiring effect and a release function that can discriminate
   * between different [[ExitCase exit cases]].
   *
   * @tparam F
   *   the effect type in which the resource is acquired and released
   * @tparam A
   *   the type of the resource
   * @param acquire
   *   a function to effectfully acquire a resource
   * @param release
   *   a function to effectfully release the resource returned by `acquire`
   */
  def makeCase[F[_], A](
      acquire: F[A]
  )(release: (A, ExitCase) => F[Unit])(implicit F: Functor[F]): Resource[F, A] =
    applyCase[F, A](acquire.map(a => (a, e => release(a, e))))

  /**
   * Creates a resource from a possibly cancelable acquiring effect and a release function.
   *
   * The acquiring effect takes a `Poll[F]` to allow for cancelable acquires, which is most
   * often useful when acquiring lock-like structures: it should be possible to interrupt a
   * fiber waiting on a lock, but if it does get acquired, release need to be guaranteed.
   *
   * Note that in this case the acquire action should know how to cleanup after itself in case
   * it gets canceled, since Resource will only guarantee release when acquire succeeds (and
   * when the actions in `use` or `flatMap` fail, succeed, or get canceled)
   *
   * @tparam F
   *   the effect type in which the resource is acquired and released
   * @tparam A
   *   the type of the resource
   * @param acquire
   *   an effect to acquire a resource, possibly interruptibly
   * @param release
   *   a function to effectfully release the resource returned by `acquire`
   */
  def makeFull[F[_], A](acquire: Poll[F] => F[A])(release: A => F[Unit])(
      implicit F: Functor[F]): Resource[F, A] =
    applyFull[F, A](poll => acquire(poll).map(a => (a, _ => release(a))))

  /**
   * Creates a resource from a possibly cancelable acquiring effect and a release function that
   * can discriminate between different [[ExitCase exit cases]].
   *
   * The acquiring effect takes a `Poll[F]` to allow for cancelable acquires, which is most
   * often useful when acquiring lock-like structures: it should be possible to interrupt a
   * fiber waiting on a lock, but if it does get acquired, release need to be guaranteed.
   *
   * Note that in this case the acquire action should know how to cleanup after itself in case
   * it gets canceled, since Resource will only guarantee release when acquire succeeds (and
   * when the actions in `use` or `flatMap` fail, succeed, or get canceled)
   *
   * @tparam F
   *   the effect type in which the resource is acquired and released
   * @tparam A
   *   the type of the resource
   * @param acquire
   *   an effect to acquire a resource, possibly interruptibly
   * @param release
   *   a function to effectfully release the resource returned by `acquire`
   */
  def makeCaseFull[F[_], A](acquire: Poll[F] => F[A])(release: (A, ExitCase) => F[Unit])(
      implicit F: Functor[F]): Resource[F, A] =
    applyFull[F, A](poll => acquire(poll).map(a => (a, e => release(a, e))))

  /**
   * Lifts a pure value into a resource. The resource has a no-op release.
   *
   * @param a
   *   the value to lift into a resource
   */
  def pure[F[_], A](a: A): Resource[F, A] =
    Pure(a)

  /**
   * A resource with a no-op allocation and a no-op release.
   */
  def unit[F[_]]: Resource[F, Unit] = pure(())

  /**
   * Lifts an applicative into a resource. The resource has a no-op release. Preserves
   * interruptibility of `fa`.
   *
   * @param fa
   *   the value to lift into a resource
   */
  def eval[F[_], A](fa: F[A]): Resource[F, A] =
    Resource.Eval(fa)

  /**
   * Lifts a finalizer into a resource. The resource has a no-op allocation.
   */
  def onFinalize[F[_]: Applicative](release: F[Unit]): Resource[F, Unit] =
    unit.onFinalize(release)

  /**
   * Creates a resource that allocates immediately without any effects, but calls `release` when
   * closing, providing the [[ExitCase the usage completed with]].
   */
  def onFinalizeCase[F[_]: Applicative](release: ExitCase => F[Unit]): Resource[F, Unit] =
    unit.onFinalizeCase(release)

  /**
   * Lifts an applicative into a resource as a `FunctionK`. The resource has a no-op release.
   */
  def liftK[F[_]]: F ~> Resource[F, *] =
    new (F ~> Resource[F, *]) {
      def apply[A](fa: F[A]): Resource[F, A] = Resource.eval(fa)
    }

  /**
   * Allocates two resources concurrently, and combines their results in a tuple.
   */
  def both[F[_]: Concurrent, A, B](
      rfa: Resource[F, A],
      rfb: Resource[F, B]): Resource[F, (A, B)] =
    rfa.both(rfb)

  /**
   * Races the evaluation of two resource allocations and returns the result of the winner,
   * except in the case of cancelation.
   */
  def race[F[_]: Concurrent, A, B](
      rfa: Resource[F, A],
      rfb: Resource[F, B]
  ): Resource[F, Either[A, B]] =
    rfa.race(rfb)

  /**
   * Creates a [[Resource]] by wrapping a Java
   * [[https://docs.oracle.com/javase/8/docs/api/java/lang/AutoCloseable.html AutoCloseable]].
   *
   * In most real world cases, implementors of AutoCloseable are blocking as well, so the close
   * action runs in the blocking context.
   *
   * @example
   *   {{{
   *   import cats.effect._
   *   import scala.io.Source
   *
   *   def reader(data: String): Resource[IO, Source] =
   *     Resource.fromAutoCloseable(IO.blocking {
   *       Source.fromString(data)
   *     })
   *   }}}
   *
   * @example
   *   {{{
   *   import cats.effect._
   *   import scala.io.Source
   *
   *   def reader[F[_]](data: String)(implicit F: Sync[F]): Resource[F, Source] =
   *     Resource.fromAutoCloseable(F.blocking {
   *       Source.fromString(data)
   *     })
   *   }}}
   *
   * @param acquire
   *   The effect with the resource to acquire.
   * @param F
   *   the effect type in which the resource was acquired and will be released
   * @tparam F
   *   the type of the effect
   * @tparam A
   *   the type of the autocloseable resource
   * @return
   *   a Resource that will automatically close after use
   */
  def fromAutoCloseable[F[_], A <: AutoCloseable](acquire: F[A])(
      implicit F: Sync[F]): Resource[F, A] =
    Resource.make(acquire)(autoCloseable => F.blocking(autoCloseable.close()))

  def canceled[F[_]](implicit F: MonadCancel[F, _]): Resource[F, Unit] =
    Resource.eval(F.canceled)

  def uncancelable[F[_], A](body: Poll[Resource[F, *]] => Resource[F, A])(
      implicit F: MonadCancel[F, Throwable]): Resource[F, A] =
    Resource applyFull { poll =>
      val inner = new Poll[Resource[F, *]] {
        def apply[B](rfb: Resource[F, B]): Resource[F, B] =
          Resource applyFull { innerPoll => innerPoll(poll(rfb.allocatedCase)) }
      }

      body(inner).allocatedCase
    }

  def unique[F[_]](implicit F: Unique[F]): Resource[F, Unique.Token] =
    Resource.eval(F.unique)

  def never[F[_], A](implicit F: GenSpawn[F, _]): Resource[F, A] =
    Resource.eval(F.never[A])

  def cede[F[_]](implicit F: GenSpawn[F, _]): Resource[F, Unit] =
    Resource.eval(F.cede)

  def deferred[F[_], A](
      implicit F: GenConcurrent[F, _]): Resource[F, Deferred[Resource[F, *], A]] =
    Resource.eval(F.deferred[A]).map(_.mapK(Resource.liftK[F]))

  def ref[F[_], A](a: A)(implicit F: GenConcurrent[F, _]): Resource[F, Ref[Resource[F, *], A]] =
    Resource.eval(F.ref(a)).map(_.mapK(Resource.liftK[F]))

  def monotonic[F[_]](implicit F: Clock[F]): Resource[F, FiniteDuration] =
    Resource.eval(F.monotonic)

  def realTime[F[_]](implicit F: Clock[F]): Resource[F, FiniteDuration] =
    Resource.eval(F.realTime)

  def suspend[F[_], A](hint: Sync.Type)(thunk: => A)(implicit F: Sync[F]): Resource[F, A] =
    Resource.eval(F.suspend(hint)(thunk))

  def sleep[F[_]](time: Duration)(implicit F: GenTemporal[F, _]): Resource[F, Unit] =
    Resource.eval(F.sleep(time))

  @deprecated("Use overload with Duration", "3.4.0")
  def sleep[F[_]](time: FiniteDuration, F: GenTemporal[F, _]): Resource[F, Unit] =
    sleep(time: Duration)(F)

  def cont[F[_], K, R](body: Cont[Resource[F, *], K, R])(implicit F: Async[F]): Resource[F, R] =
    Resource.applyFull { poll =>
      poll {
        F.cont {
          new Cont[F, K, (R, Resource.ExitCase => F[Unit])] {
            def apply[G[_]](implicit G: MonadCancel[G, Throwable]) = { (cb, ga, nt) =>
              type D[A] = Kleisli[G, Ref[G, ExitCase => F[Unit]], A]

              val nt2 = new (Resource[F, *] ~> D) {
                def apply[A](rfa: Resource[F, A]) =
                  Kleisli { r =>
                    G uncancelable { poll =>
                      poll(nt(rfa.allocatedCase)) flatMap {
                        case (a, fin) =>
                          r.update(f => (ec: ExitCase) => f(ec) !> (F.unit >> fin(ec))).as(a)
                      }
                    }
                  }
              }

              nt(F.ref((_: ExitCase) => F.unit).map(_.mapK(nt))) flatMap { r =>
                G.guaranteeCase(
                  (body[D].apply(cb, Kleisli.liftF(ga), nt2).run(r), r.get).tupled) {
                  case Outcome.Succeeded(_) => G.unit
                  case oc => r.get.flatMap(fin => nt(fin(ExitCase.fromOutcome(oc))))
                }
              }
            }
          }
        }
      }
    }

  def executionContext[F[_]](implicit F: Async[F]): Resource[F, ExecutionContext] =
    Resource.eval(F.executionContext)

  def raiseError[F[_], A, E](e: E)(implicit F: ApplicativeError[F, E]): Resource[F, A] =
    Resource.eval(F.raiseError[A](e))

  /**
   * `Resource` data constructor that wraps an effect allocating a resource, along with its
   * finalizers.
   */
  final case class Allocate[F[_], A](resource: Poll[F] => F[(A, ExitCase => F[Unit])])
      extends Resource[F, A]

  /**
   * `Resource` data constructor that encodes the `flatMap` operation.
   */
  final case class Bind[F[_], S, +A](source: Resource[F, S], fs: S => Resource[F, A])
      extends Resource[F, A]

  final case class Pure[F[_], +A](a: A) extends Resource[F, A]

  final case class Eval[F[_], A](fa: F[A]) extends Resource[F, A]

  /**
   * Type for signaling the exit condition of an effectful computation, that may either succeed,
   * fail with an error or get canceled.
   *
   * The types of exit signals are:
   *
   *   - [[ExitCase$.Succeeded Succeeded]]: for successful completion
   *   - [[ExitCase$.Errored Errored]]: for termination in failure
   *   - [[ExitCase$.Canceled Canceled]]: for abortion
   */
  sealed trait ExitCase extends Product with Serializable {
    def toOutcome[F[_]: Applicative]: Outcome[F, Throwable, Unit]
  }

  object ExitCase {

    /**
     * An [[ExitCase]] that signals successful completion.
     *
     * Note that "successful" is from the type of view of the `MonadCancel` type.
     *
     * When combining such a type with `EitherT` or `OptionT` for example, this exit condition
     * might not signal a successful outcome for the user, but it does for the purposes of the
     * `bracket` operation. <-- TODO still true?
     */
    case object Succeeded extends ExitCase {
      def toOutcome[F[_]](implicit F: Applicative[F]): Outcome.Succeeded[F, Throwable, Unit] =
        Outcome.Succeeded(F.unit)
    }

    /**
     * An [[ExitCase]] signaling completion in failure.
     */
    final case class Errored(e: Throwable) extends ExitCase {
      def toOutcome[F[_]: Applicative]: Outcome.Errored[F, Throwable, Unit] =
        Outcome.Errored(e)
    }

    /**
     * An [[ExitCase]] signaling that the action was aborted.
     *
     * As an example this can happen when we have a cancelable data type, like IO and the task
     * yielded by `bracket` gets canceled when it's at its `use` phase.
     */
    case object Canceled extends ExitCase {
      def toOutcome[F[_]: Applicative]: Outcome.Canceled[F, Throwable, Unit] =
        Outcome.Canceled()
    }

    def fromOutcome[F[_], A](outcome: Outcome[F, Throwable, A]): ExitCase =
      outcome match {
        case Outcome.Succeeded(_) => Succeeded
        case Outcome.Errored(t) => Errored(t)
        case Outcome.Canceled() => Canceled
      }
  }

  implicit final class NestedSyntax[F[_], A](val self: Resource[Resource[F, *], A])
      extends AnyVal {

    /**
     * Flattens the outer [[Resource]] scope with the inner, mirroring the semantics of
     * [[Resource.flatMap]].
     *
     * This function is useful in cases where some generic combinator (such as
     * [[GenSpawn.background]]) explicitly returns a value within a [[Resource]] effect, and
     * that generic combinator is itself used within an outer [[Resource]]. In this case, it is
     * often desirable to flatten the inner and outer [[Resource]] together. [[flattenK]]
     * implements this flattening operation with the same semantics as [[Resource.flatMap]].
     */
    def flattenK(implicit F: MonadCancel[F, Throwable]): Resource[F, A] =
      Resource.applyFull { poll =>
        val alloc = self.allocatedCase.allocatedCase

        poll(alloc) map {
          case ((a, rfin), fin) =>
            val composedFinalizers =
              (ec: ExitCase) =>
                // Break stack-unsafe mutual recursion with allocatedCase
                (F.unit >> fin(ec))
                  .guarantee(F.unit >> rfin(ec).allocatedCase.flatMap(_._2(ec)))
            (a, composedFinalizers)
        }
      }
  }

  type Par[F[_], A] = ParallelF[Resource[F, *], A]

  implicit def parallelForResource[F[_]: Concurrent]: Parallel.Aux[Resource[F, *], Par[F, *]] =
    spawn.parallelForGenSpawn[Resource[F, *], Throwable]

  implicit def commutativeApplicativeForResource[F[_]: Concurrent]
      : CommutativeApplicative[Par[F, *]] =
    spawn.commutativeApplicativeForParallelF[Resource[F, *], Throwable]
}

private[effect] trait ResourceHOInstances0 extends ResourceHOInstances1 {
  implicit def catsEffectAsyncForResource[F[_]](implicit F0: Async[F]): Async[Resource[F, *]] =
    new ResourceAsync[F] {
      def F = F0
    }

  implicit def catsEffectSemigroupKForResource[F[_], A](
      implicit F0: MonadCancel[F, Throwable],
      K0: SemigroupK[F],
      G0: Ref.Make[F]): ResourceSemigroupK[F] =
    new ResourceSemigroupK[F] {
      def F = F0
      def K = K0
      def G = G0
    }
}

private[effect] trait ResourceHOInstances1 extends ResourceHOInstances2 {
  implicit def catsEffectTemporalForResource[F[_]](
      implicit F0: Temporal[F]): Temporal[Resource[F, *]] =
    new ResourceTemporal[F] {
      def F = F0
    }

  implicit def catsEffectSyncForResource[F[_]](implicit F0: Sync[F]): Sync[Resource[F, *]] =
    new ResourceSync[F] {
      def F = F0
      def rootCancelScope = F0.rootCancelScope
    }
}

private[effect] trait ResourceHOInstances2 extends ResourceHOInstances3 {
  implicit def catsEffectConcurrentForResource[F[_]](
      implicit F0: Concurrent[F]): Concurrent[Resource[F, *]] =
    new ResourceConcurrent[F] {
      def F = F0
    }

  implicit def catsEffectClockForResource[F[_]](
      implicit F0: Clock[F],
      FA: Applicative[Resource[F, *]]): Clock[Resource[F, *]] =
    new ResourceClock[F] {
      def F = F0
      def applicative = FA
    }

  final implicit def catsEffectDeferForResource[F[_]]: Defer[Resource[F, *]] =
    new ResourceDefer[F]
}

private[effect] trait ResourceHOInstances3 extends ResourceHOInstances4 {
  implicit def catsEffectMonadCancelForResource[F[_]](
      implicit F0: MonadCancel[F, Throwable]): MonadCancel[Resource[F, *], Throwable] =
    new ResourceMonadCancel[F] {
      def F = F0
      def rootCancelScope = F0.rootCancelScope
    }
}

private[effect] trait ResourceHOInstances4 extends ResourceHOInstances5 {
  implicit def catsEffectMonadErrorForResource[F[_], E](
      implicit F0: MonadError[F, E]): MonadError[Resource[F, *], E] =
    new ResourceMonadError[F, E] {
      def F = F0
    }
}

private[effect] trait ResourceHOInstances5 {
  implicit def catsEffectMonadForResource[F[_]]: Monad[Resource[F, *]] =
    new ResourceMonad[F]

  @deprecated("Use overload without constraint", "3.4.0")
  def catsEffectMonadForResource[F[_]](F: Monad[F]): Monad[Resource[F, *]] = {
    val _ = F
    catsEffectMonadForResource[F]
  }
}

abstract private[effect] class ResourceFOInstances0 extends ResourceFOInstances1 {
  implicit def catsEffectMonoidForResource[F[_], A](
      implicit A0: Monoid[A]): Monoid[Resource[F, A]] =
    new ResourceMonoid[F, A] {
      def A = A0
    }

  @deprecated("Use overload without monad constraint", "3.4.0")
  def catsEffectMonoidForResource[F[_], A](
      F0: Monad[F],
      A0: Monoid[A]): Monoid[Resource[F, A]] = {
    val _ = F0
    catsEffectMonoidForResource(A0)
  }
}

abstract private[effect] class ResourceFOInstances1 {
  implicit def catsEffectSemigroupForResource[F[_], A](
      implicit A0: Semigroup[A]): ResourceSemigroup[F, A] =
    new ResourceSemigroup[F, A] {
      def A = A0
    }

  @deprecated("Use overload without monad constraint", "3.4.0")
  def catsEffectSemigroupForResource[F[_], A](
      F0: Monad[F],
      A0: Semigroup[A]): ResourceSemigroup[F, A] = {
    val _ = F0
    catsEffectSemigroupForResource[F, A](A0)
  }
}

abstract private[effect] class ResourceMonadCancel[F[_]]
    extends ResourceMonadError[F, Throwable]
    with MonadCancel[Resource[F, *], Throwable] {
  implicit protected def F: MonadCancel[F, Throwable]

  def canceled: Resource[F, Unit] = Resource.canceled

  def forceR[A, B](fa: Resource[F, A])(fb: Resource[F, B]): Resource[F, B] =
    fa.forceR(fb)

  def onCancel[A](fa: Resource[F, A], fin: Resource[F, Unit]): Resource[F, A] =
    fa.onCancel(fin)

  def uncancelable[A](body: Poll[Resource[F, *]] => Resource[F, A]): Resource[F, A] =
    Resource.uncancelable(body)

  override def guaranteeCase[A](rfa: Resource[F, A])(
      fin: Outcome[Resource[F, *], Throwable, A] => Resource[F, Unit]): Resource[F, A] =
    rfa.guaranteeCase(fin)
}

// note: Spawn alone isn't possible since we need Concurrent to implement start
abstract private[effect] class ResourceConcurrent[F[_]]
    extends ResourceMonadCancel[F]
    with GenConcurrent[Resource[F, *], Throwable] {
  implicit protected def F: Concurrent[F]

  def unique: Resource[F, Unique.Token] = Resource.unique

  def never[A]: Resource[F, A] = Resource.never

  def cede: Resource[F, Unit] = Resource.cede

  def start[A](fa: Resource[F, A]): Resource[F, Fiber[Resource[F, *], Throwable, A]] =
    fa.start

  def deferred[A]: Resource[F, Deferred[Resource[F, *], A]] =
    Resource.deferred

  def ref[A](a: A): Resource[F, Ref[Resource[F, *], A]] =
    Resource.ref(a)

  override def both[A, B](fa: Resource[F, A], fb: Resource[F, B]): Resource[F, (A, B)] =
    fa.both(fb)

  override def race[A, B](fa: Resource[F, A], fb: Resource[F, B]): Resource[F, Either[A, B]] =
    fa.race(fb)

  override def memoize[A](fa: Resource[F, A]): Resource[F, Resource[F, A]] = {
    Resource.eval(F.ref(List.empty[Resource.ExitCase => F[Unit]])).flatMap { release =>
      val fa2 = F.uncancelable { poll =>
        poll(fa.allocatedCase).flatMap { case (a, r) => release.update(r :: _).as(a) }
      }
      Resource
        .makeCaseFull[F, F[A]](poll => poll(F.memoize(fa2))) { (_, exit) =>
          release.get.flatMap(_.foldMapM(_(exit)))
        }
        .map(memo => Resource.eval(memo))
    }
  }
}

private[effect] trait ResourceClock[F[_]] extends Clock[Resource[F, *]] {
  implicit protected def F: Clock[F]

  def monotonic: Resource[F, FiniteDuration] =
    Resource.monotonic

  def realTime: Resource[F, FiniteDuration] =
    Resource.realTime
}

private[effect] trait ResourceSync[F[_]]
    extends ResourceMonadCancel[F]
    with ResourceClock[F]
    with Sync[Resource[F, *]] {
  implicit protected def F: Sync[F]

  def suspend[A](hint: Sync.Type)(thunk: => A): Resource[F, A] =
    Resource.suspend(hint)(thunk)
}

private[effect] trait ResourceTemporal[F[_]]
    extends ResourceConcurrent[F]
    with ResourceClock[F]
    with Temporal[Resource[F, *]] {
  implicit protected def F: Temporal[F]

  def sleep(time: FiniteDuration): Resource[F, Unit] =
    Resource.sleep(time)
}

abstract private[effect] class ResourceAsync[F[_]]
    extends ResourceTemporal[F]
    with ResourceSync[F]
    with Async[Resource[F, *]] { self =>
  implicit protected def F: Async[F]

  override def unique: Resource[F, Unique.Token] =
    Resource.unique

  override def syncStep[G[_], A](fa: Resource[F, A], limit: Int)(
      implicit G: Sync[G]): G[Either[Resource[F, A], A]] =
    fa match {
      case Pure(a) => G.pure(Right(a))
      case Resource.Eval(fa) =>
        G.map(F.syncStep[G, A](F.widen(fa), limit)) {
          case Left(fa) => Left(Resource.eval(fa))
          case Right(a) => Right(a)
        }
      case r => G.pure(Left(r))
    }

  override def never[A]: Resource[F, A] =
    Resource.never

  def cont[K, R](body: Cont[Resource[F, *], K, R]): Resource[F, R] =
    Resource.cont(body)

  def evalOn[A](fa: Resource[F, A], ec: ExecutionContext): Resource[F, A] =
    fa.evalOn(ec)

  def executionContext: Resource[F, ExecutionContext] =
    Resource.executionContext
}

abstract private[effect] class ResourceMonadError[F[_], E]
    extends ResourceMonad[F]
    with MonadError[Resource[F, *], E] {

  implicit protected def F: MonadError[F, E]

  override def attempt[A](fa: Resource[F, A]): Resource[F, Either[E, A]] =
    fa.attempt

  def handleErrorWith[A](fa: Resource[F, A])(f: E => Resource[F, A]): Resource[F, A] =
    fa.handleErrorWith(f)

  def raiseError[A](e: E): Resource[F, A] =
    Resource.raiseError[F, A, E](e)
}

private[effect] class ResourceMonad[F[_]]
    extends Monad[Resource[F, *]]
    with StackSafeMonad[Resource[F, *]] {

  def pure[A](a: A): Resource[F, A] =
    Resource.pure(a)

  def flatMap[A, B](fa: Resource[F, A])(f: A => Resource[F, B]): Resource[F, B] =
    fa.flatMap(f)
}

abstract private[effect] class ResourceMonoid[F[_], A]
    extends ResourceSemigroup[F, A]
    with Monoid[Resource[F, A]] {
  implicit protected def A: Monoid[A]

  def empty: Resource[F, A] = Resource.pure[F, A](A.empty)
}

abstract private[effect] class ResourceSemigroup[F[_], A] extends Semigroup[Resource[F, A]] {
  implicit protected def A: Semigroup[A]

  def combine(rx: Resource[F, A], ry: Resource[F, A]): Resource[F, A] =
    rx.combine(ry)
}

abstract private[effect] class ResourceSemigroupK[F[_]] extends SemigroupK[Resource[F, *]] {
  implicit protected def F: MonadCancel[F, Throwable]
  implicit protected def K: SemigroupK[F]
  implicit protected def G: Ref.Make[F]

  def combineK[A](ra: Resource[F, A], rb: Resource[F, A]): Resource[F, A] =
    ra.combineK(rb)
}

private[effect] final class ResourceDefer[F[_]] extends Defer[Resource[F, *]] {
  def defer[A](fa: => Resource[F, A]): Resource[F, A] = Resource.unit.flatMap(_ => fa)
}
