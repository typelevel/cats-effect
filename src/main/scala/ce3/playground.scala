/*
 * Copyright 2019 Daniel Spiewak
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

import cats.{~>, Id, InjectK, Monad}
import cats.data.{Const, EitherK}
import cats.implicits._

object playground {

  sealed trait StateF[S, A] extends Product with Serializable

  object StateF {
    final case class Get[S]() extends StateF[S, S]
    final case class Put[S](s: S) extends StateF[S, Unit]
  }

  object State {
    def get[F[_], S](implicit I: InjectK[StateF[S, ?], F]): Free[F, S] =
      Free.inject[StateF[S, ?], F](StateF.Get[S]())

    def put[F[_], S](s: S)(implicit I: InjectK[StateF[S, ?], F]): Free[F, Unit] =
      Free.inject[StateF[S, ?], F](StateF.Put[S](s))

    def modify[F[_], S](f: S => S)(implicit I: InjectK[StateF[S, ?], F]): Free[F, Unit] =
      modifyF[F, S](f.andThen(_.pure[Free[F, ?]]))

    def modifyF[F[_], S](f: S => Free[F, S])(implicit I: InjectK[StateF[S, ?], F]): Free[F, Unit] =
      get[F, S].flatMap(s => f(s).flatMap(put[F, S](_)))
  }

  final case class Never[A]()
  final case class Fork[F[_], A](body: F[_])

  type FiberId = Int
  val MainFiberId = 0

  type Results = Map[FiberId, Option[_]]   // None represents cancelation

  type PureConcurrentF[F[_], E, A] =
    EitherK[
      StateF[Results, ?],    // fiber results
      EitherK[
        StateF[FiberId, ?],       // current fiber id
        EitherK[
          Never,
          EitherK[
            Fork[F, ?],
            ExitCase[Id, E, ?],
            ?],
          ?],
        ?],
      A]

  // this is a class because we need the recursion for Fork
  final case class PureConcurrent[E, A](body: Free[PureConcurrent.F[E, ?], A])

  object PureConcurrent {
    type F[E, A] = PureConcurrentF[PureConcurrent[E, ?], E, A]
  }

  def ResultsI[E] = InjectK[StateF[Results, ?], PureConcurrent.F[E, ?]]
  def FiberIdI[E] = InjectK[StateF[FiberId, ?], PureConcurrent.F[E, ?]]
  def NeverI[E] = InjectK[Never, PureConcurrent.F[E, ?]]
  def ForkI[E] = InjectK[Fork[PureConcurrent[E, ?], ?], PureConcurrent.F[E, ?]]
  def ExitCaseI[E] = InjectK[ExitCase[Id, E, ?], PureConcurrent.F[E, ?]]

  def nextFiberId[E]: PureConcurrent[E, FiberId] =
    PureConcurrent(State.get[PureConcurrent.F[E, ?], FiberId])

  def run[E, A](pc: PureConcurrent[E, A]): ExitCase[Option, E, A] = ???

  /**
   * Implements a cooperative parallel interpreter for Free with a global state space,
   * given injections for state access and recursive forks. The state is
   * shared across all simultaneous suspensions and will survive short-circuiting
   * in the target monad (if relevant). Note that callers are responsible for
   * ensuring that any short circuiting in the target monad is handled prior to
   * suspending the actual fork, otherwise the short circuiting will propagate
   * through the entire system and terminate all suspensions.
   *
   * "Daemon" semantics correspond to a mode in which forked suspensions with
   * outstanding continuations are ignored if the main suspension reaches
   * completion (either Pure or Suspend). This is analogous to how daemon threads
   * will not prevent the termination of the JVM, even if they still have outstanding
   * work when the main thread terminates. Non-daemon semantics will cause the
   * final result to be held pending the resolution of all forked suspensions.
   *
   * Sequence orders are deterministic, but not guaranteed to follow any particular
   * order. In other words, race conditions can and will happen, but if they resolve
   * in a particular direction in one run, they will resolve in the same direction
   * on the next run unless other changes are made. Changes to the structure of the
   * forked Free(s) (such as adding or removing an extra flatMap) can and will have
   * an impact on evaluation order, and thus race condition resolution.
   *
   * Fairness is not considered. If you care about fairness, you probably also care
   * about other little things like... uh... performance.
   */
  def forkFoldFree[S[_], U, A, M[_]: Monad](
      target: Free[S, A],
      universe: U)(
      f: S ~> M,
      daemon: Boolean = true)(
      implicit IU: InjectK[StateF[U, ?], S],
      IF: InjectK[(Free[S, _], ?), S])
      : M[(U, A)] = {

    // non-empty list of fibers where we know the type of the head and nothing else
    def runAll(universe: U, main: Free[S, A], fibers: List[Free[S, _]]): M[(U, A)] = {
      type StepLeft[X] = M[X]
      type StepRight[X] = (Free[S, X], Option[Free[S, _]])
      type StepRightList[X] = (Free[S, X], List[Free[S, _]])

      type Step[X] = Either[StepLeft[X], StepRight[X]]

      def stepFiber[X](universe: U, fiber: Free[S, X]): M[(U, Step[X])] = {
        fiber.foldStep(
          onPure = a => a.pure[M].asLeft[StepRight[X]].pure[M].tupleLeft(universe),

          onSuspend = { sa =>
            val backM = IF.prj(sa) match {
              case Some((fiber, a)) =>
                (Free.pure[S, X](a), Some(fiber): Option[Free[S, _]]).asRight[StepLeft[X]].pure[M]

              case None =>
                f(sa).asLeft[StepRight[X]].pure[M]
            }

            backM.tupleLeft(universe)
          },

          onFlatMapped = { pair =>
            val (front, cont) = pair

            IU.prj(front) match {
              case Some(StateF.Get()) =>
                (cont(universe.asInstanceOf), None: Option[Free[S, _]]).asRight[StepLeft[X]].pure[M].tupleLeft(universe)

              case Some(StateF.Put(universe2)) =>
                (cont(().asInstanceOf), None: Option[Free[S, _]]).asRight[StepLeft[X]].pure[M].tupleLeft(universe2)

              case None =>
                // the asInstanceOf usage here is getting around a bug in scalac, where it simply isn't unifying the existential with itself
                IF.prj(front) match {
                  case Some((fiber, x)) =>
                    (cont(x.asInstanceOf), Some(fiber): Option[Free[S, _]]).asRight[StepLeft[X]].pure[M].tupleLeft(universe)

                  case None =>
                    f(front).map(x => (cont(x.asInstanceOf), None: Option[Free[S, _]]).asRight[StepLeft[X]]).tupleLeft(universe)
                }
            }
          })
      }

      val daemonized = stepFiber(universe, main) flatMap { tuple =>
        tuple traverse {
          case Left(ma) =>
            // if non-daemon semantics and we still have outstanding fibers, transform back into a Right
            if (daemon || fibers.isEmpty)
              ma.asLeft[StepRightList[A]].pure[M]
            else
              ma.map(a => (Free.pure[S, A](a), fibers).asRight[StepLeft[A]])

          case Right((main, forked)) =>
            (main, forked.map(_ :: fibers).getOrElse(fibers)).asRight[StepLeft[A]].pure[M]
        }
      }

      daemonized flatMap {
        case (universe, Left(ma)) =>
          ma.tupleLeft(universe)

        case (universe, Right((main2, fibers))) =>
          // amusingly, we reverse the list with each step... that's technically okay
          val fibers2M = fibers.foldLeftM((universe, List[Free[S, _]]())) {
            case ((universe, acc), fiber) =>
              stepFiber(universe, fiber) flatMap { tuple =>
                tuple traverse {
                  // fiber completed; drop it from the list
                  case Left(ma) =>
                    ma.as(acc)    // ensure we sequence the final action... and then drop the results

                  // fiber stepped, optionally forking
                  case Right((fiber2, forked2)) =>
                    (fiber2 :: forked2.map(_ :: acc).getOrElse(acc)).pure[M]
                }
              }
          }

          fibers2M flatMap {
            case (universe, fibers2) =>
              runAll(universe, main2, fibers2)
          }
      }
    }

    runAll(universe, target, Nil)
  }
}
