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

import cats.{Eq, Order, Show}
import cats.effect.testkit.{AsyncGenerators, BracketGenerators, GenK, OutcomeGenerators, TestContext}
import cats.implicits._

import org.scalacheck.{Arbitrary, Cogen, Gen, Prop}

import org.specs2.matcher.Matcher
import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.TimeUnit

trait BaseSpec extends Specification with Runners
