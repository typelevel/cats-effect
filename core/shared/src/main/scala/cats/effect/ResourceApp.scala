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

package cats.effect

import cats.syntax.all._

/**
 * A convenience trait for defining applications which are entirely within [[Resource]]. This is
 * implemented as a relatively straightforward wrapper around [[IOApp]] and thus inherits most
 * of its functionality and semantics.
 *
 * This trait should generally be used for any application which would otherwise trivially end
 * with [[cats.effect.kernel.Resource!.use]] (or one of its variants). For example:
 *
 * {{{
 *   object HttpExample extends IOApp {
 *     def run(args: List[String]) = {
 *       val program = for {
 *         config <- Resource.eval(loadConfig(args.head))
 *         postgres <- Postgres[IO](config.jdbcUri)
 *         endpoints <- ExampleEndpoints[IO](config, postgres)
 *         _ <- HttpServer[IO](config.host, config.port, endpoints)
 *       } yield ()
 *
 *       program.useForever.as(ExitCode.Success)
 *     }
 *   }
 * }}}
 *
 * This example assumes some underlying libraries like
 * [[https://tpolecat.github.io/skunk/ Skunk]] and [[https://http4s.org Http4s]], but otherwise
 * it represents a relatively typical example of what the main class for a realistic Cats Effect
 * application might look like. Notably, the whole thing is enclosed in `Resource`, which is
 * `use`d at the very end. This kind of pattern is so common that `ResourceApp` defines a
 * special trait which represents it. We can rewrite the above example:
 *
 * {{{
 *   object HttpExample extends ResourceApp.Forever {
 *     def run(args: List[String]) =
 *       for {
 *         config <- Resource.eval(loadConfig(args.head))
 *         db <- Postgres[IO](config.jdbcUri)
 *         endpoints <- ExampleEndpoints[IO](config, db)
 *         _ <- HttpServer[IO](config.host, config.port, endpoints)
 *       } yield ()
 *   }
 * }}}
 *
 * These two programs are equivalent.
 *
 * @see
 *   [[run]]
 * @see
 *   [[ResourceApp.Simple]]
 * @see
 *   [[ResourceApp.Forever]]
 */
trait ResourceApp { self =>

  /**
   * @see
   *   [[IOApp.run]]
   */
  def run(args: List[String]): Resource[IO, ExitCode]

  final def main(args: Array[String]): Unit = {
    val ioApp = new IOApp {
      override def run(args: List[String]): IO[ExitCode] =
        self.run(args).use(IO.pure(_))
    }

    ioApp.main(args)
  }
}

object ResourceApp {

  /**
   * A [[ResourceApp]] which takes no process arguments and always produces [[ExitCode.Success]]
   * except when an exception is raised.
   *
   * @see
   *   [[IOApp.Simple]]
   */
  trait Simple extends ResourceApp {

    /**
     * @see
     *   [[cats.effect.IOApp.Simple!.run:cats\.effect\.IO[Unit]*]]
     */
    def run: Resource[IO, Unit]

    final def run(args: List[String]): Resource[IO, ExitCode] = run.as(ExitCode.Success)
  }

  /**
   * A [[ResourceApp]] which runs until externally interrupted (with `SIGINT`), at which point
   * all finalizers will be run and the application will shut down upon completion. This is an
   * extremely common pattern in practical Cats Effect applications and is particularly
   * applicable to network servers.
   *
   * @see
   *   [[cats.effect.kernel.Resource!.useForever]]
   */
  trait Forever { self =>

    /**
     * Identical to [[ResourceApp.run]] except that it delegates to
     * [[cats.effect.kernel.Resource!.useForever]] instead of
     * [[cats.effect.kernel.Resource!.use]].
     *
     * @see
     *   [[ResourceApp.run]]
     */
    def run(args: List[String]): Resource[IO, Unit]

    final def main(args: Array[String]): Unit = {
      val ioApp = new IOApp {
        override def run(args: List[String]): IO[ExitCode] =
          self.run(args).useForever
      }

      ioApp.main(args)
    }
  }
}
