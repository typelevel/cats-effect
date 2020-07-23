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

import cats.{~>, MonadError}
import cats.data.OptionT
import cats.syntax.all._

trait Fiber[F[_], E, A] {
  def cancel: F[Unit]
  def join: F[Outcome[F, E, A]]

  def joinAndEmbed(onCancel: F[A])(implicit F: Concurrent[F, E]): F[A] =
    join.flatMap(_.fold(onCancel, F.raiseError(_), fa => fa))

  def joinAndEmbedNever(implicit F: Concurrent[F, E]): F[A] =
    joinAndEmbed(F.canceled *> F.never)
}

trait Concurrent[F[_], E] extends MonadError[F, E] {

  def start[A](fa: F[A]): F[Fiber[F, E, A]]

  def uncancelable[A](body: (F ~> F) => F[A]): F[A]

  // produces an effect which is already canceled (and doesn't introduce an async boundary)
  // this is effectively a way for a fiber to commit suicide and yield back to its parent
  // The fallback (unit) value is produced if the effect is sequenced into a block in which
  // cancelation is suppressed.
  def canceled: F[Unit]

  def onCancel[A](fa: F[A], fin: F[Unit]): F[A]

  def guarantee[A](fa: F[A], fin: F[Unit]): F[A] =
    guaranteeCase(fa)(_ => fin)

  def guaranteeCase[A](fa: F[A])(fin: Outcome[F, E, A] => F[Unit]): F[A] =
    bracketCase(unit)(_ => fa)((_, oc) => fin(oc))

  def bracket[A, B](acquire: F[A])(use: A => F[B])(release: A => F[Unit]): F[B] =
    bracketCase(acquire)(use)((a, _) => release(a))

  def bracketCase[A, B](acquire: F[A])(use: A => F[B])(
      release: (A, Outcome[F, E, B]) => F[Unit]): F[B] =
    bracketFull(_ => acquire)(use)(release)

  def bracketFull[A, B](acquire: (F ~> F) => F[A])(use: A => F[B])(
      release: (A, Outcome[F, E, B]) => F[Unit]): F[B] =
    uncancelable { poll =>
      flatMap(acquire(poll)) { a =>
        val finalized = onCancel(poll(use(a)), release(a, Outcome.Canceled()))
        val handled = onError(finalized) {
          case e => void(attempt(release(a, Outcome.Errored(e))))
        }
        flatMap(handled)(b => as(attempt(release(a, Outcome.Completed(pure(b)))), b))
      }
    }

  // produces an effect which never returns
  def never[A]: F[A]

  // introduces a fairness boundary by yielding control to the underlying dispatcher
  def cede: F[Unit]

  def racePair[A, B](fa: F[A], fb: F[B])
      : F[Either[(Outcome[F, E, A], Fiber[F, E, B]), (Fiber[F, E, A], Outcome[F, E, B])]]

  def raceOutcome[A, B](fa: F[A], fb: F[B]): F[Either[Outcome[F, E, A], Outcome[F, E, B]]] =
    uncancelable { _ =>
      flatMap(racePair(fa, fb)) {
        case Left((oc, f)) => as(f.cancel, Left(oc))
        case Right((f, oc)) => as(f.cancel, Right(oc))
      }
    }

  def race[A, B](fa: F[A], fb: F[B]): F[Either[A, B]] =
    uncancelable { poll =>
      flatMap(racePair(fa, fb)) {
        case Left((oc, f)) =>
          oc match {
            case Outcome.Completed(fa) => productR(f.cancel)(map(fa)(Left(_)))
            case Outcome.Errored(ea) => productR(f.cancel)(raiseError(ea))
            case Outcome.Canceled() =>
              flatMap(onCancel(poll(f.join), f.cancel)) {
                case Outcome.Completed(fb) => map(fb)(Right(_))
                case Outcome.Errored(eb) => raiseError(eb)
                case Outcome.Canceled() => productR(canceled)(never)
              }
          }
        case Right((f, oc)) =>
          oc match {
            case Outcome.Completed(fb) => productR(f.cancel)(map(fb)(Right(_)))
            case Outcome.Errored(eb) => productR(f.cancel)(raiseError(eb))
            case Outcome.Canceled() =>
              flatMap(onCancel(poll(f.join), f.cancel)) {
                case Outcome.Completed(fa) => map(fa)(Left(_))
                case Outcome.Errored(ea) => raiseError(ea)
                case Outcome.Canceled() => productR(canceled)(never)
              }
          }
      }
    }

  def bothOutcome[A, B](fa: F[A], fb: F[B]): F[(Outcome[F, E, A], Outcome[F, E, B])] =
    uncancelable { poll =>
      flatMap(racePair(fa, fb)) {
        case Left((oc, f)) => map(onCancel(poll(f.join), f.cancel))((oc, _))
        case Right((f, oc)) => map(onCancel(poll(f.join), f.cancel))((_, oc))
      }
    }

  def both[A, B](fa: F[A], fb: F[B]): F[(A, B)] =
    uncancelable { poll =>
      flatMap(racePair(fa, fb)) {
        case Left((oc, f)) =>
          oc match {
            case Outcome.Completed(fa) =>
              flatMap(onCancel(poll(f.join), f.cancel)) {
                case Outcome.Completed(fb) => product(fa, fb)
                case Outcome.Errored(eb) => raiseError(eb)
                case Outcome.Canceled() => productR(canceled)(never)
              }
            case Outcome.Errored(ea) => productR(f.cancel)(raiseError(ea))
            case Outcome.Canceled() => productR(f.cancel)(productR(canceled)(never))
          }
        case Right((f, oc)) =>
          oc match {
            case Outcome.Completed(fb) =>
              flatMap(onCancel(poll(f.join), f.cancel)) {
                case Outcome.Completed(fa) => product(fa, fb)
                case Outcome.Errored(ea) => raiseError(ea)
                case Outcome.Canceled() => productR(canceled)(never)
              }
            case Outcome.Errored(eb) => productR(f.cancel)(raiseError(eb))
            case Outcome.Canceled() => productR(f.cancel)(productR(canceled)(never))
          }
      }
    }
}

object Concurrent {
  def apply[F[_], E](implicit F: Concurrent[F, E]): F.type = F
  def apply[F[_]](implicit F: Concurrent[F, _], d: DummyImplicit): F.type = F

  trait OptionTConcurrent[F[_], E] extends Concurrent[OptionT[F, *], E] {

    implicit protected def F: Concurrent[F, E]

    def start[A](fa: OptionT[F, A]): OptionT[F, Fiber[OptionT[F, *], E, A]] = ???

    def uncancelable[A](
        body: (OptionT[F, *] ~> OptionT[F, *]) => OptionT[F, A]): OptionT[F, A] = ???

    def canceled: OptionT[F, Unit] = OptionT.liftF(F.canceled)

    def onCancel[A](fa: OptionT[F, A], fin: OptionT[F, Unit]): OptionT[F, A] =
      OptionT(F.onCancel(fa.value, fin.value.as(())))

    def never[A]: OptionT[F, A] = OptionT.liftF(F.never)

    def cede: OptionT[F, Unit] = OptionT.liftF(F.cede)

    def racePair[A, B](fa: OptionT[F, A], fb: OptionT[F, B]): OptionT[
      F,
      Either[
        (Outcome[OptionT[F, *], E, A], Fiber[OptionT[F, *], E, B]),
        (Fiber[OptionT[F, *], E, A], Outcome[OptionT[F, *], E, B])]] = ???
  }
}
