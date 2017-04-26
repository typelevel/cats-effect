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

package cats.effect

import cats.effect.laws.util.TestContext
import cats.kernel.Eq
import org.scalactic.source
import org.scalatest.prop.Checkers
import org.scalatest.{FunSuite, Matchers, Tag}
import org.typelevel.discipline.Laws
import org.typelevel.discipline.scalatest.Discipline
import scala.concurrent.{ExecutionException, Future}
import scala.util.{Failure, Success}

class BaseTestsSuite extends FunSuite with Matchers with Checkers with Discipline {
  /** For tests that need a usable [[TestContext]] reference. */
  def testAsync[A](name: String, tags: Tag*)(f: TestContext => Unit)
    (implicit pos: source.Position): Unit = {

    test(name, tags:_*)(f(TestContext()))(pos)
  }

  /** For discipline tests. */
  def checkAllAsync(name: String, f: TestContext => Laws#RuleSet) {
    val context = TestContext()
    val ruleSet = f(context)

    for ((id, prop) ← ruleSet.all.properties)
      test(name + "." + id) {
        check(prop)
      }
  }

  implicit def eqThrowable: Eq[Throwable] =
    Eq.fromUniversalEquals[Throwable]

  /**
   * Defines equality for `IO` references that can
   * get interpreted by means of a [[TestContext]].
   */
  implicit def eqIO[A](implicit A: Eq[A], ec: TestContext): Eq[IO[A]] =
    new Eq[IO[A]] {
      def eqv(x: IO[A], y: IO[A]): Boolean =
        eqFuture[A].eqv(x.unsafeToFuture(), y.unsafeToFuture())
    }

  /**
   * Defines equality for `Future` references that can
   * get interpreted by means of a [[TestContext]].
   */
  implicit def eqFuture[A](implicit A: Eq[A], ec: TestContext): Eq[Future[A]] =
    new Eq[Future[A]] {
      def eqv(x: Future[A], y: Future[A]): Boolean = {
        // Executes the whole pending queue of runnables
        ec.tick()

        x.value match {
          case None =>
            y.value.isEmpty
          case Some(Success(a)) =>
            y.value match {
              case Some(Success(b)) => A.eqv(a, b)
              case _ => false
            }
          case Some(Failure(ex1)) =>
            y.value match {
              case Some(Failure(ex2)) =>
                extractEx(ex1) == extractEx(ex2)
              case _ =>
                false
            }
        }
      }

      // Unwraps exceptions that got caught by Future's implementation
      // and that got wrapped in ExecutionException (`Future(throw ex)`)
      def extractEx(ex: Throwable): Throwable =
        ex match {
          case ref: ExecutionException =>
            Option(ref.getCause).getOrElse(ref)
          case _ => ex
        }
    }
}
