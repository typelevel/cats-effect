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

trait FileDescriptorPoller {

  /**
   * Registers a file descriptor with the poller and monitors read- and/or write-ready events.
   */
  def registerFileDescriptor(
      fileDescriptor: Int,
      monitorReadReady: Boolean,
      monitorWriteReady: Boolean
  ): Resource[IO, FileDescriptorPollHandle]

}

object FileDescriptorPoller {
  def find: IO[Option[FileDescriptorPoller]] =
    IO.pollers.map(_.collectFirst { case poller: FileDescriptorPoller => poller })

  def get = find.flatMap(
    _.liftTo[IO](new RuntimeException("No FileDescriptorPoller installed in this IORuntime"))
  )
}

trait FileDescriptorPollHandle {

  /**
   * Recursively invokes `f` until it is no longer blocked. Typically `f` will call `read` or
   * `recv` on the file descriptor.
   *   - If `f` fails because the file descriptor is blocked, then it should return `Left[A]`.
   *     Then `f` will be invoked again with `A` at a later point, when the file handle is ready
   *     for reading.
   *   - If `f` is successful, then it should return a `Right[B]`. The `IO` returned from this
   *     method will complete with `B`.
   */
  def pollReadRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B]

  /**
   * Recursively invokes `f` until it is no longer blocked. Typically `f` will call `write` or
   * `send` on the file descriptor.
   *   - If `f` fails because the file descriptor is blocked, then it should return `Left[A]`.
   *     Then `f` will be invoked again with `A` at a later point, when the file handle is ready
   *     for writing.
   *   - If `f` is successful, then it should return a `Right[B]`. The `IO` returned from this
   *     method will complete with `B`.
   */
  def pollWriteRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B]

}
