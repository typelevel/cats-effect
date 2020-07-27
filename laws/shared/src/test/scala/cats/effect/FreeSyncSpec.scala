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

package cats.effect
package laws

import cats.{Eq, Show}
import cats.data.{EitherT, IorT, Kleisli, OptionT, ReaderWriterStateT, StateT, WriterT}
import cats.laws.discipline.arbitrary._
import cats.laws.discipline.{eq, MiniInt}; import eq._
import cats.effect.testkit.{freeEval, FreeSyncGenerators, SyncTypeGenerators}, freeEval._
import cats.implicits._

import org.scalacheck.Prop
import org.scalacheck.util.Pretty

import org.specs2.ScalaCheck
import org.specs2.mutable._

import org.typelevel.discipline.specs2.mutable.Discipline

class FreeSyncSpec extends Specification with Discipline with ScalaCheck with BaseSpec {
  import FreeSyncGenerators._
  import SyncTypeGenerators._

  implicit def prettyFromShow[A: Show](a: A): Pretty =
    Pretty.prettyString(a.show)

  implicit val eqThrowable: Eq[Throwable] =
    Eq.fromUniversalEquals

  implicit def exec(sbool: FreeEitherSync[Boolean]): Prop =
    run(sbool).fold(Prop.exception(_), b => if (b) Prop.proved else Prop.falsified)

  checkAll("FreeEitherSync", SyncTests[FreeEitherSync].sync[Int, Int, Int])
  checkAll("OptionT[FreeEitherSync]", SyncTests[OptionT[FreeEitherSync, *]].sync[Int, Int, Int])
  checkAll(
    "EitherT[FreeEitherSync]",
    SyncTests[EitherT[FreeEitherSync, Int, *]].sync[Int, Int, Int])
  checkAll(
    "StateT[FreeEitherSync]",
    SyncTests[StateT[FreeEitherSync, MiniInt, *]].sync[Int, Int, Int])
  checkAll(
    "WriterT[FreeEitherSync]",
    SyncTests[WriterT[FreeEitherSync, Int, *]].sync[Int, Int, Int])
  checkAll("IorT[FreeEitherSync]", SyncTests[IorT[FreeEitherSync, Int, *]].sync[Int, Int, Int])
  checkAll(
    "Kleisli[FreeEitherSync]",
    SyncTests[Kleisli[FreeEitherSync, MiniInt, *]].sync[Int, Int, Int])
  checkAll(
    "ReaderWriterStateT[FreeEitherSync]",
    SyncTests[ReaderWriterStateT[FreeEitherSync, MiniInt, Int, MiniInt, *]].sync[Int, Int, Int])
}
