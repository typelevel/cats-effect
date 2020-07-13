---
layout: docsplus
title:  "Tutorial"
position: 2
---

<nav role="navigation" id="toc"></nav>

## Introduction

This tutorial tries to help newcomers to cats-effect to get familiar with its
main concepts by means of code examples, in a learn-by-doing fashion. Two small
programs will be coded. The first one copies the contents from one file to
another, safely handling resources in the process. That should help us to flex
our muscles. The second one is a bit more elaborated, it is a light TCP server
able to attend concurrent connections. In both cases complexity will grow as we
add more features, which will allow to introduce more and more concepts from
cats-effect. Also, while the first example is focused on `IO`, the second one
will shift towards polymorphic functions that make use of cats-effect type
classes and do not tie our code to `IO`.

This tutorial assumes certain familiarity with functional programming. It is
also a good idea to read cats-effect documentation prior to starting this
tutorial, at least the 
[excellent documentation about `IO` data type](../datatypes/io.md).

Please read this tutorial as training material, not as a best-practices
document. As you gain more experience with cats-effect, probably you will find
your own solutions to deal with the problems presented here. Also, bear in mind
that using cats-effect for copying files or building TCP servers is suitable for
a 'getting things done' approach, but for more complex
systems/settings/requirements you might want to take a look at
[fs2](http://fs2.io) or [Monix](https://monix.io) to find powerful network and
file abstractions that integrate with cats-effect. But that is beyond the
purpose of this tutorial, which focuses solely on cats-effect.

That said, let's go!

## Setting things up

This [Github repo](https://github.com/lrodero/cats-effect-tutorial) includes all
the software that will be developed during this tutorial. It uses `sbt` as the
build tool. To ease coding, compiling and running the code snippets in this
tutorial it is recommended to use the same `build.sbt`, or at least one with the
same dependencies and compilation options:

```scala
name := "cats-effect-tutorial"

version := "1.0"

scalaVersion := "2.12.8"

libraryDependencies += "org.typelevel" %% "cats-effect" % "1.3.0" withSources() withJavadoc()

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps",
  "-language:higherKinds",
  "-Ypartial-unification")
```

Code snippets in this tutorial can be pasted and compiled right in the scala
console of the project defined above (or any project with similar settings).

## Copying contents of a file - safely handling resources

Our goal is to create a program that copies files. First we will work on a
function that carries such task, and then we will create a program that can be
invoked from the shell and uses that function.

First of all we must code the function that copies the content from a file to
another file. The function takes the source and destination files as parameters.
But this is functional programming! So invoking the function shall not copy
anything, instead it will return an `IO` instance that encapsulates all the
side effects involved (opening/closing files, reading/writing content), that way
_purity_ is kept.  Only when that `IO` instance is evaluated all those
side-effectful actions will be run. In our implementation the `IO` instance will
return the amount of bytes copied upon execution, but this is just a design
decision. Of course errors can occur, but when working with any `IO` those
should be embedded in the `IO` instance. That is, no exception is raised outside
the `IO` and so no `try` (or the like) needs to be used when using the function,
instead the `IO` evaluation will fail and the `IO` instance will carry the error
raised.

Now, the signature of our function looks like this:

```scala
import cats.effect.IO
import java.io.File

def copy(origin: File, destination: File): IO[Long] = ???
```

Nothing scary, uh? As we said before, the function just returns an `IO`
instance. When run, all side-effects will be actually executed and the `IO`
instance will return the bytes copied in a `Long` (note that `IO` is
parameterized by the return type). Now, let's start implementing our function.
First, we need to open two streams that will read and write file contents.

### Acquiring and releasing `Resource`s
We consider opening a stream to be a side-effect action, so we have to
encapsulate those actions in their own `IO` instances. For this, we will make
use of cats-effect `Resource`, that allows to orderly create, use and then
release resources. See this code:

```scala
import cats.effect.{IO, Resource}
import cats.implicits._ 
import java.io._ 

def inputStream(f: File): Resource[IO, FileInputStream] =
  Resource.make {
    IO(new FileInputStream(f))                         // build
  } { inStream =>
    IO(inStream.close()).handleErrorWith(_ => IO.unit) // release
  }

def outputStream(f: File): Resource[IO, FileOutputStream] =
  Resource.make {
    IO(new FileOutputStream(f))                         // build 
  } { outStream =>
    IO(outStream.close()).handleErrorWith(_ => IO.unit) // release
  }

def inputOutputStreams(in: File, out: File): Resource[IO, (InputStream, OutputStream)] =
  for {
    inStream  <- inputStream(in)
    outStream <- outputStream(out)
  } yield (inStream, outStream)
```

We want to ensure that streams are closed once we are done using them, no matter
what. That is precisely why we use `Resource` in both `inputStream` and
`outputStream` functions, each one returning one `Resource` that encapsulates
the actions for opening and then closing each stream.  `inputOutputStreams`
encapsulates both resources in a single `Resource` instance that will be
available once the creation of both streams has been successful, and only in
that case. As seen in the code above `Resource` instances can be combined in
for-comprehensions as they implement `flatMap`. Note also that when releasing
resources we must also take care of any possible error during the release
itself, for example with the `.handleErrorWith` call as we do in the code above.
In this case we just swallow the error, but normally it should be at least
logged.

Optionally we could have used `Resource.fromAutoCloseable` to define our
resources, that method creates `Resource` instances over objects that implement
`java.lang.AutoCloseable` interface without having to define how the resource is
released. So our `inputStream` function would look like this:

```scala
import cats.effect.{IO, Resource}
import java.io.{File, FileInputStream}

def inputStream(f: File): Resource[IO, FileInputStream] =
  Resource.fromAutoCloseable(IO(new FileInputStream(f)))
```

That code is way simpler, but with that code we would not have control over what
would happen if the closing operation throws an exception. Also it could be that
we want to be aware when closing operations are being run, for example using
logs. In contrast, using `Resource.make` allows to easily control the actions
of the release phase.

Let's go back to our `copy` function, which now looks like this:

```scala
import cats.effect.{IO, Resource}
import java.io._

// as defined before
def inputOutputStreams(in: File, out: File): Resource[IO, (InputStream, OutputStream)] = ???

// transfer will do the real work
def transfer(origin: InputStream, destination: OutputStream): IO[Long] = ???

def copy(origin: File, destination: File): IO[Long] = 
  inputOutputStreams(origin, destination).use { case (in, out) => 
    transfer(in, out)
  }
```

The new method `transfer` will perform the actual copying of data, once the
resources (the streams) are obtained. When they are not needed anymore, whatever
the outcome of `transfer` (success or failure) both streams will be closed. If
any of the streams could not be obtained, then `transfer` will not be run. Even
better, because of `Resource` semantics, if there is any problem opening the
input file then the output file will not be opened.  On the other hand, if there
is any issue opening the output file, then the input stream will be closed.

### What about `bracket`?
Now, if you are familiar with cats-effect's `bracket` you may be wondering why
we are not using it as it looks so similar to `Resource` (and there is a good
reason for that: `Resource` is based on `bracket`). Ok, before moving forward it
is worth to take a look to `bracket`.

There are three stages when using `bracket`: _resource acquisition_, _usage_,
and _release_. Each stage is defined by an `IO` instance.  A fundamental
property is that the _release_ stage will always be run regardless whether the
_usage_ stage finished correctly or an exception was thrown during its
execution. In our case, in the _acquisition_ stage we would create the streams,
then in the _usage_ stage we will copy the contents, and finally in the release
stage we will close the streams.  Thus we could define our `copy` function as
follows:

```scala
import cats.effect.IO
import cats.implicits._ 
import java.io._ 

// function inputOutputStreams not needed

// transfer will do the real work
def transfer(origin: InputStream, destination: OutputStream): IO[Long] = ???

def copy(origin: File, destination: File): IO[Long] = {
  val inIO: IO[InputStream]  = IO(new FileInputStream(origin))
  val outIO:IO[OutputStream] = IO(new FileOutputStream(destination))

  (inIO, outIO)              // Stage 1: Getting resources 
    .tupled                  // From (IO[InputStream], IO[OutputStream]) to IO[(InputStream, OutputStream)]
    .bracket{
      case (in, out) =>
        transfer(in, out)    // Stage 2: Using resources (for copying data, in this case)
    } {
      case (in, out) =>      // Stage 3: Freeing resources
        (IO(in.close()), IO(out.close()))
        .tupled              // From (IO[Unit], IO[Unit]) to IO[(Unit, Unit)]
        .handleErrorWith(_ => IO.unit).void
    }
}
```

New `copy` definition is more complex, even though the code as a whole is way
shorter as we do not need the `inputOutputStreams` function. But there is a
catch in the code above.  When using `bracket`, if there is a problem when
getting resources in the first stage, then the release stage will not be run.
Now, in the code above, first the origin file and then the destination file are
opened (`tupled` just reorganizes both `IO` instances into a single one). So
what would happen if we successfully open the origin file (_i.e._ when
evaluating `inIO`) but then an exception is raised when opening the destination
file (_i.e._ when evaluating `outIO`)? In that case the origin stream will not
be closed! To solve this we should first get the first stream with one `bracket`
call, and then the second stream with another `bracket` call inside the first.
But, in a way, that's precisely what we do when we `flatMap` instances of
`Resource`. And the code looks cleaner too. So, while using `bracket` directly
has its place, `Resource` is likely to be a better choice when dealing with
multiple resources at once.

### Copying data 
Finally we have our streams ready to go! We have to focus now on coding
`transfer`. That function will have to define a loop that at each iteration
reads data from the input stream into a buffer, and then writes the buffer
contents into the output stream. At the same time, the loop will keep a counter
of the bytes transferred. To reuse the same buffer we should define it outside
the main loop, and leave the actual transmission of data to another function
`transmit` that uses that loop. Something like:

```scala
import cats.effect.IO
import cats.implicits._ 
import java.io._ 

def transmit(origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): IO[Long] =
  for {
    amount <- IO(origin.read(buffer, 0, buffer.size))
    count  <- if(amount > -1) IO(destination.write(buffer, 0, amount)) >> transmit(origin, destination, buffer, acc + amount)
              else IO.pure(acc) // End of read stream reached (by java.io.InputStream contract), nothing to write
  } yield count // Returns the actual amount of bytes transmitted

def transfer(origin: InputStream, destination: OutputStream): IO[Long] =
  for {
    buffer <- IO(new Array[Byte](1024 * 10)) // Allocated only when the IO is evaluated
    total  <- transmit(origin, destination, buffer, 0L)
  } yield total
```

Take a look at `transmit`, observe that both input and output actions are
encapsulated in (suspended in) `IO`. `IO` being a monad, we can sequence them
using a for-comprehension to create another `IO`. The for-comprehension loops as
long as the call to `read()` does not return a negative value that would signal
that the end of the stream has been reached. `>>` is a Cats operator to sequence
two operations where the output of the first is not needed by the second (_i.e._
it is equivalent to `first.flatMap(_ => second)`). In the code above that means
that after each write operation we recursively call `transmit` again, but as
`IO` is stack safe we are not concerned about stack overflow issues. At each
iteration we increase the counter `acc` with the amount of bytes read at that
iteration. 

We are making progress, and already have a version of `copy` that can be used.
If any exception is raised when `transfer` is running, then the streams will be
automatically closed by `Resource`. But there is something else we have to take
into account: `IO` instances execution can be **_canceled!_**. And cancellation
should not be ignored, as it is a key feature of cats-effect. We will discuss
cancellation in the next section.

### Dealing with cancellation
Cancellation is a powerful but non-trivial cats-effect feature. In cats-effect,
some `IO` instances can be cancelable, meaning that their evaluation will be
aborted. If the programmer is careful, an alternative `IO` task will be run
under cancellation, for example to deal with potential cleaning up activities.
We will see how an `IO` can be actually canceled at the end of the [Fibers are
not threads! section](#fibers-are-not-threads) later on, but for now we will
just keep in mind that during the execution of the `IO` returned by the `copy`
method a cancellation could be requested at any time.

Now, `IO`s created with `Resource.use` can be canceled. The cancellation will
trigger the execution of the code that handles the closing of the resource. In
our case, that would close both streams. So far so good! But what happens if
cancellation happens _while_ the streams are being used? This could lead to
data corruption as a stream where some thread is writing to is at the same time
being closed by another thread. For more info about this problem see [Gotcha:
Cancellation is a concurrent
action](../datatypes/io.md#gotcha-cancellation-is-a-concurrent-action) in
cats-effect site.

To prevent such data corruption we must use some concurrency control mechanism
that ensures that no stream will be closed while the `IO` returned by
`transfer` is being evaluated.  Cats-effect provides several constructs for
controlling concurrency, for this case we will use a
[_semaphore_](../concurrency/semaphore.md). A semaphore has a number of
permits, its method `.acquire` 'blocks' if no permit is available until
`release` is called on the same semaphore. It is important to remark that
_there is no actual thread being really blocked_, the thread that finds the
`.acquire` call will be immediately recycled by cats-effect. When the `release`
method is invoked then cats-effect will look for some available thread to
resume the execution of the code after `.acquire`.

We will use a semaphore with a single permit. The `.withPermit` method acquires
one permit, runs the `IO` given and then releases the permit.  We could also
use `.acquire` and then `.release` on the semaphore explicitly, but
`.withPermit` is more idiomatic and ensures that the permit is released even if
the effect run fails.

```scala
import cats.implicits._
import cats.effect.{Concurrent, IO, Resource}
import cats.effect.concurrent.Semaphore
import java.io._

// transfer and transmit methods as defined before
def transfer(origin: InputStream, destination: OutputStream): IO[Long] = ???

def inputStream(f: File, guard: Semaphore[IO]): Resource[IO, FileInputStream] =
  Resource.make {
    IO(new FileInputStream(f))
  } { inStream => 
    guard.withPermit {
     IO(inStream.close()).handleErrorWith(_ => IO.unit)
    }
  }

def outputStream(f: File, guard: Semaphore[IO]): Resource[IO, FileOutputStream] =
  Resource.make {
    IO(new FileOutputStream(f))
  } { outStream =>
    guard.withPermit {
     IO(outStream.close()).handleErrorWith(_ => IO.unit)
    }
  }

def inputOutputStreams(in: File, out: File, guard: Semaphore[IO]): Resource[IO, (InputStream, OutputStream)] =
  for {
    inStream  <- inputStream(in, guard)
    outStream <- outputStream(out, guard)
  } yield (inStream, outStream)

def copy(origin: File, destination: File)(implicit concurrent: Concurrent[IO]): IO[Long] = {
  for {
    guard <- Semaphore[IO](1)
    count <- inputOutputStreams(origin, destination, guard).use { case (in, out) => 
               guard.withPermit(transfer(in, out))
             }
  } yield count
}
```

Before calling to `transfer` we acquire the semaphore, which is not released
until `transfer` is done. The `use` call ensures that the semaphore will be
released under any circumstances, whatever the result of `transfer` (success,
error, or cancellation). As the 'release' parts in the `Resource` instances are
now blocked on the same semaphore, we can be sure that streams are closed only
after `transfer` is over, _i.e._ we have implemented mutual exclusion of
`transfer` execution and resources releasing. An implicit `Concurrent` instance
is required to create the semaphore instance, which is included in the function
signature.

Mark that while the `IO` returned by `copy` is cancelable (because so are `IO`
instances returned by `Resource.use`), the `IO` returned by `transfer` is not.
Trying to cancel it will not have any effect and that `IO` will run until the
whole file is copied! In real world code you will probably want to make your
functions cancelable, section [Building cancelable IO
tasks](../datatypes/io.md#building-cancelable-io-tasks) of `IO` documentation
explains how to create such cancelable `IO` instances (besides calling
`Resource.use`, as we have done for our code).

And that is it! We are done, now we can create a program that uses this
`copy` function.

### `IOApp` for our final program

We will create a program that copies files, this program only takes two
parameters: the name of the origin and destination files. For coding this
program we will use `IOApp` as it allows to maintain purity in our definitions
up to the program main function.

`IOApp` is a kind of 'functional' equivalent to Scala's `App`, where instead of
coding an effectful `main` method we code a pure `run` function. When executing
the class a `main` method defined in `IOApp` will call the `run` function we
have coded. Any interruption (like pressing `Ctrl-c`) will be treated as a
cancellation of the running `IO`. Also `IOApp` provides implicit instances of
`Timer[IO]` and `ContextShift[IO]` (not discussed yet in this tutorial).
`ContextShift[IO]` allows for having a `Concurrent[IO]` in scope, as the one
required by the `copy` function.

When coding `IOApp`, instead of a `main` function we have a `run` function,
which creates the `IO` instance that forms the program. In our case, our `run`
method can look like this:

```scala
import cats.effect._
import cats.implicits._
import java.io.File

object Main extends IOApp {

  // copy as defined before
  def copy(origin: File, destination: File): IO[Long] = ???

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _      <- if(args.length < 2) IO.raiseError(new IllegalArgumentException("Need origin and destination files"))
                else IO.unit
      orig = new File(args(0))
      dest = new File(args(1))
      count <- copy(orig, dest)
      _     <- IO(println(s"$count bytes copied from ${orig.getPath} to ${dest.getPath}"))
    } yield ExitCode.Success
}
```

Heed how `run` verifies the `args` list passed. If there are fewer than two
arguments, an error is raised. As `IO` implements `MonadError` we can at any
moment call to `IO.raiseError` to interrupt a sequence of `IO` operations.

#### Copy program code
You can check the [final version of our copy program
here](https://github.com/lrodero/cats-effect-tutorial/blob/master/src/main/scala/catsEffectTutorial/CopyFile.scala).

The program can be run from `sbt` just by issuing this call:

```scala
> runMain catsEffectTutorial.CopyFile origin.txt destination.txt
```

It can be argued that using `IO{java.nio.file.Files.copy(...)}` would get an
`IO` with the same characteristics of purity as our function. But there is a
difference: our `IO` is safely cancelable! So the user can stop the running code
at any time for example by pressing `Ctrl-c`, our code will deal with safe
resource release (streams closing) even under such circumstances. The same will
apply if the `copy` function is run from other modules that require its
functionality. If the `IO` returned by this function is canceled while being
run, still resources will be properly released. But recall what we commented
before: this is because `use` returns `IO` instances that are cancelable, in
contrast our `transfer` function is not cancelable.

WARNING: To properly test cancelation, You should also ensure that
`fork := true` is set in the sbt configuration, otherwise sbt will
intercept the cancelation because it will be running the program
in the same JVM as itself.

### Polymorphic cats-effect code
There is an important characteristic of `IO` that we shall be aware of. `IO` is
able to encapsulate side-effects, but the capacity to define concurrent and/or
async and/or cancelable `IO` instances comes from the existence of a
`Concurrent[IO]` instance. `Concurrent[F[_]]` is a type class that, for an `F`
carrying a side-effect, brings the ability to cancel or start concurrently the
side-effect in `F`. `Concurrent` also extends type class `Async[F[_]]`, that
allows to define synchronous/asynchronous computations. `Async[F[_]]`, in turn,
extends type class `Sync[F[_]]`, which can suspend the execution of side effects
in `F`.

So well, `Sync` can suspend side effects (and so can `Async` and `Concurrent` as
they extend `Sync`). We have used `IO` so far mostly for that purpose. Now,
going back to the code we created to copy files, could have we coded its
functions in terms of some `F[_]: Sync` instead of `IO`? Truth is we could and
**in fact it is recommendable** in real world programs.  See for example how we
would define a polymorphic version of our `transfer` function with this
approach, just by replacing any use of `IO` by calls to the `delay` and `pure`
methods of the `Sync[F[_]]` instance!

```scala
import cats.effect.Sync
import cats.effect.syntax.all._
import cats.implicits._
import java.io._

def transmit[F[_]: Sync](origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): F[Long] =
  for {
    amount <- Sync[F].delay(origin.read(buffer, 0, buffer.size))
    count  <- if(amount > -1) Sync[F].delay(destination.write(buffer, 0, amount)) >> transmit(origin, destination, buffer, acc + amount)
              else Sync[F].pure(acc) // End of read stream reached (by java.io.InputStream contract), nothing to write
  } yield count // Returns the actual amount of bytes transmitted
```

We can do the same transformation to most of the code we have created so far,
but not all. In `copy` you will find out that we do need a full instance of
`Concurrent[F]` in scope, this is because it is required by the `Semaphore`
instantiation:

```scala
import cats.effect._
import cats.effect.concurrent.Semaphore
import cats.effect.syntax.all._
import cats.implicits._
import java.io._

def transfer[F[_]: Sync](origin: InputStream, destination: OutputStream): F[Long] = ???
def inputOutputStreams[F[_]: Sync](in: File, out: File, guard: Semaphore[F]): Resource[F, (InputStream, OutputStream)] = ???

def copy[F[_]: Concurrent](origin: File, destination: File): F[Long] = 
  for {
    guard <- Semaphore[F](1)
    count <- inputOutputStreams(origin, destination, guard).use { case (in, out) => 
               guard.withPermit(transfer(in, out))
             }
  } yield count
```

Only in our `main` function we will set `IO` as the final `F` for
our program. To do so, of course, a `Concurrent[IO]` instance must be in scope,
but that instance is brought transparently by `IOApp` so we do not need to be
concerned about it.

During the remaining of this tutorial we will use polymorphic code, only falling
to `IO` in the `run` method of our `IOApp`s. Polymorphic code is less
restrictive, as functions are not tied to `IO` but are applicable to any `F[_]`
as long as there is an instance of the type class required (`Sync[F[_]]` ,
`Concurrent[F[_]]`...) in scope. The type class to use will depend on the
requirements of our code. For example, if the execution of the side-effect
should be cancelable, then we must stick to `Concurrent[F[_]]`. Also, it is
actually easier to work on `F` than on any specific type.

#### Copy program code, polymorphic version
The polymorphic version of our copy program in full is available
[here](https://github.com/lrodero/cats-effect-tutorial/blob/master/src/main/scala/catsEffectTutorial/CopyFilePolymorphic.scala).

### Exercises: improving our small `IO` program

To finalize we propose you some exercises that will help you to keep improving
your IO-kungfu:

1. Modify the `IOApp` so it shows an error and abort the execution if the origin
   and destination files are the same, the origin file cannot be open for
   reading or the destination file cannot be opened for writing. Also, if the
   destination file already exists, the program should ask for confirmation
   before overwriting that file.
2. Modify `transmit` so the buffer size is not hardcoded but passed as
   parameter.
3. Use some other concurrency tool of cats-effect instead of `semaphore` to
   ensure mutual exclusion of `transfer` execution and streams closing.
4. Create a new program able to copy folders. If the origin folder has
   subfolders, then their contents must be recursively copied too. Of course the
   copying must be safely cancelable at any moment.

## TCP echo server - concurrent system with `Fiber`s

This program is a bit more complex than the previous one. Here we create an echo
TCP server that replies to each text message from a client sending back that
same message. When the client sends an empty line its connection is shutdown by
the server. This server will also bring a key feature, it will be able to attend
several clients at the same time. For that we will use `cats-effect`'s `Fiber`,
which can be seen as light threads. For each new client a `Fiber` instance will
be spawned to serve that client.

We will stick to a simple design principle: _whoever method creates a resource
is the sole responsible of dispatching it!_  It's worth to remark this from the
beginning to better understand the code listings shown in this tutorial.

Ok, we are ready to start coding our server. Let's build it step-by-step. First
we will code a method that implements the echo protocol. It will take as input
the socket (`java.net.Socket` instance) that is connected to the client. The
method will be basically a loop that at each iteration reads the input from the
client, if the input is not an empty line then the text is sent back to the
client, otherwise the method will finish.

The method signature will look like this:

```scala
import cats.effect.Sync
import java.net.Socket
def echoProtocol[F[_]: Sync](clientSocket: Socket): F[Unit] = ???
```

Reading and writing will be done using `java.io.BufferedReader` and
`java.io.BufferedWriter` instances built from the socket. Recall that this
method will be in charge of closing those buffers, but not the client socket (it
did not create that socket after all!). We will use again `Resource` to ensure
that we close the streams we create. Also, all actions with potential
side-effects are encapsulated in `F` instances, where `F` only requires an
implicit instance of `Sync[F]` to be present. That way we ensure no side-effect
is actually run until the `F` returned by this method is evaluated.  With this
in mind, the code looks like:

```scala
import cats.effect._
import cats.implicits._
import java.io._
import java.net._

def echoProtocol[F[_]: Sync](clientSocket: Socket): F[Unit] = {

  def loop(reader: BufferedReader, writer: BufferedWriter): F[Unit] = for {
    line <- Sync[F].delay(reader.readLine())
    _    <- line match {
              case "" => Sync[F].unit // Empty line, we are done
              case _  => Sync[F].delay{ writer.write(line); writer.newLine(); writer.flush() } >> loop(reader, writer)
            }
  } yield ()

  def reader(clientSocket: Socket): Resource[F, BufferedReader] =
    Resource.make {
      Sync[F].delay( new BufferedReader(new InputStreamReader(clientSocket.getInputStream())) )
    } { reader =>
      Sync[F].delay(reader.close()).handleErrorWith(_ => Sync[F].unit)
    }

  def writer(clientSocket: Socket): Resource[F, BufferedWriter] =
    Resource.make {
      Sync[F].delay( new BufferedWriter(new PrintWriter(clientSocket.getOutputStream())) )
    } { writer =>
      Sync[F].delay(writer.close()).handleErrorWith(_ => Sync[F].unit)
    }

  def readerWriter(clientSocket: Socket): Resource[F, (BufferedReader, BufferedWriter)] =
    for {
      reader <- reader(clientSocket)
      writer <- writer(clientSocket)
    } yield (reader, writer)

  readerWriter(clientSocket).use { case (reader, writer) =>
    loop(reader, writer) // Let's get to work
  }
}
```

Note that, as we did in the previous example, we swallow possible errors when
closing the streams, as there is little to do in such cases.

The actual interaction with the client is done by the `loop` function. It tries
to read a line from the client, and if successful then it checks the line
content. If empty it finishes the method, if not it sends back the line through
the writer and loops back to the beginning. And what happens if we find any
error in the `reader.readLine()` call? Well, `F` will catch the exception and
will short-circuit the evaluation, this method would then return an `F`
instance carrying the caught exception. Easy, right :) ?

So we are done with our `echoProtocol` method, good! But we still miss the part
of our server that will listen for new connections and create fibers to attend
them. Let's work on that, we implement that functionality in another method
that takes as input the `java.io.ServerSocket` instance that will listen for
clients:

```scala
import cats.effect._
import cats.effect.syntax.all._
import cats.effect.ExitCase._
import cats.implicits._
import java.net.{ServerSocket, Socket}

// echoProtocol as defined before
def echoProtocol[F[_]: Sync](clientSocket: Socket): F[Unit] = ???

def serve[F[_]: Concurrent](serverSocket: ServerSocket): F[Unit] = {
  def close(socket: Socket): F[Unit] = 
    Sync[F].delay(socket.close()).handleErrorWith(_ => Sync[F].unit)

  for {
    _ <- Sync[F]
      .delay(serverSocket.accept())
      .bracketCase { socket =>
        echoProtocol(socket)
          .guarantee(close(socket))                 // Ensuring socket is closed
          .start                                    // Will run in its own Fiber!
      }{ (socket, exit) => exit match {
        case Completed => Sync[F].unit
        case Error(_) | Canceled => close(socket)
      }}
          _ <- serve(serverSocket)                  // Looping back to the beginning
  } yield ()
}
```

We invoke the `accept` method of `ServerSocket` and use `bracketCase` to define
both the action that will make use of the resource (the client socket) and how
it will be released. The action in this case invokes `echoProtocol`, and then
uses `guarantee` call on the returned `F` to ensure that the socket will be
safely closed when `echoProtocol` is done. Also quite interesting: we use
`start`! By doing so the `echoProtocol` call will run on its own fiber thus
not blocking the main loop. To be able to invoke `start` we need an instance of
`Concurrent[F]` in scope (in fact we are invoking `Concurrent[F].start(...)`
but the `cats.effect.syntax.all._` classes that we are importing did the
trick). Finally, the release part of the `bracketCase` will only close the
socket if there was an error or cancellation during the `accept` call or the
subsequent invocation to `echoProtocol`. If that is not the case, it means that
`echoProtocol` was started without any issue and so we do not need to take any
action, the `guarantee` call will close the socket when `echoProtocol` is done.

You may wonder if using `bracketCase` when we already have `guarantee` is not a
bit overkill. We could have coded our loop like this:

```scala
for {
  socket <- Sync[F].delay(serverSocket.accept)
  _      <- echoProtocol(socket)
              .guarantee(close(socket))
              .start
  _      <- serve(serverSocket)            
} yield ()
```

That code is way simpler, but it contains a bug: if there is a cancellation in
the `flatMap` between `socket` and `echoProtocol` then `close(socket)` does not
execute. Using `bracketCase` solves that problem.

So there it is, we have our concurrent code ready, able to handle several client
connections at once!

_NOTE: If you have coded servers before, probably you are wondering if
cats-effect provides some magical way to attend an unlimited number of clients
without balancing the load somehow. Truth is, it doesn't. You can spawn as many
fibers as you wish, but there is no guarantee they will run simultaneously. More
about this in the [Fibers are not threads!](#fibers-are-not-threads)_ section.

### `IOApp` for our server
So, what do we miss now? Only the creation of the server socket of course,
which we can already do in the `run` method of an `IOApp`:

```scala
import cats.effect._
import cats.implicits._
import java.net.ServerSocket

object Main extends IOApp {
  
  // serve as defined before
  def serve[F[_]: Concurrent](serverSocket: ServerSocket): F[Unit] = ???
  
  def run(args: List[String]): IO[ExitCode] = {
  
    def close[F[_]: Sync](socket: ServerSocket): F[Unit] =
      Sync[F].delay(socket.close()).handleErrorWith(_ => Sync[F].unit)
  
    IO( new ServerSocket(args.headOption.map(_.toInt).getOrElse(5432)) )
      .bracket{
        serverSocket => serve[IO](serverSocket) >> IO.pure(ExitCode.Success)
      } {
        serverSocket => close[IO](serverSocket) >> IO(println("Server finished"))
      }
  }
}
```

Heed how this time we can use `bracket` right ahead, as there is a single
resource to deal with and no action to be taken if the creation fails. Also
`IOApp` provides a `ContextShift` in scope that brings a `Concurrent[IO]`, so we
do not have to create our own.

#### Echo server code, simple version
Full code of our basic echo server is available
[here](https://github.com/lrodero/cats-effect-tutorial/blob/master/src/main/scala/catsEffectTutorial/EchoServerV1_Simple.scala).

As before you can run in for example from the `sbt` console just by typing
 
```scala
> runMain catsEffectTutorial.EchoServerV1_Simple
```

That will start the server on default port `5432`, you can also set any other
port by passing it as argument. To test the server is properly running, you can
connect to it using `telnet`. Here we connect, send `hi`, and the server replies
with the same text. Finally we send an empty line to close the connection:

```console
$ telnet localhost 5432
Trying 127.0.0.1...
Connected to localhost.
Escape character is '^]'.
hi
hi

Connection closed by foreign host.
```

You can connect several telnet sessions at the same time to verify that indeed
our server can attend all of them simultaneously. Several... but not many, more
about that in [Fibers are not threads!](#fibers-are-not-threads) section.

Unfortunately this server is a bit too simplistic. For example, how can we stop
it? Well, that is something we have not addressed yet and it is when things can
get a bit more complicated. We will deal with proper server halting in the next
section.


### Graceful server stop (handling exit events)

There is no way to shutdown gracefully the echo server coded in the previous
section. Sure we can always `Ctrl-c` it, but proper servers should provide
better mechanisms to stop them. In this section we use some other `cats-effect`
types to deal with this.

First, we will use a flag to signal when the server shall quit. The main server
loop will run on its own fiber, that will be canceled when that flag is set.
The flag will be an instance of `MVar`. The `cats-effect` documentation
describes `MVar` as _a mutable location that can be empty or contains a value,
asynchronously blocking reads when empty and blocking writes when full_. Why not
using `Semaphore` or `Deferred`? Thing is, as we will see later on, we will need
to be able to 'peek' whether a value has been written or not in a non-blocking
fashion. That's a handy feature that `MVar` implements.

So, we will 'block' by reading our `MVar` instance, and we will only write on it
when `STOP` is received, the write being the _signal_ that the server must be
shut down. The server will be only stopped once, so we are not concerned about
blocking on writing.

And who shall signal that the server must be stopped? In this example, we will
assume that it will be the connected users who can request the server to halt by
sending a `STOP` message. Thus, the method attending clients (`echoProtocol`!)
needs access to the flag to use it to communicate that the server must stop when
that message is received.

Let's first define a new method `server` that instantiates the flag, runs the
`serve` method in its own fiber and waits on the flag to be set. Only when
the flag is set the server fiber will be canceled. The cancellation itself (the
call to `fiber.cancel`) is also run in a separate fiber to prevent being
blocked by it. This is not always needed, but the cancellation of actions
defined by `bracket` or `bracketCase` will wait until all finalizers (release
stage of bracket) are finished. The `F` created by our `serve` function is
defined based on `bracketCase`, so if the action is blocked at any bracket stage
(acquisition, usage or release), then the cancel call will be blocked too. And
our bracket blocks as the `serverSocket.accept` call is blocking!. As a result,
invoking `.cancel` will block our `server` function. To fix this we just execute
the cancellation on its own fiber by running `.cancel.start`.

```scala
import cats.effect._
import cats.effect.syntax.all._
import cats.effect.concurrent.MVar
import cats.implicits._
import java.net.ServerSocket

// serve now requires access to the stopFlag, it will use it to signal the
// server must stop
def serve[F[_]: Concurrent](serverSocket: ServerSocket, stopFlag: MVar[F, Unit]): F[Unit] = ???

def server[F[_]: Concurrent](serverSocket: ServerSocket): F[ExitCode] =
  for {
    stopFlag    <- MVar[F].empty[Unit]
    serverFiber <- serve(serverSocket, stopFlag).start // Server runs on its own Fiber
    _           <- stopFlag.read                       // Blocked until 'stopFlag.put(())' is run
    _           <- serverFiber.cancel.start            // Stopping server!
  } yield ExitCode.Success
```

As before, creating new fibers requires a `Concurrent[F]` in scope.

We must also modify the main `run` method in `IOApp` so now it calls to `server`:

```scala
import cats.effect._
import cats.implicits._
import java.net.ServerSocket

object Main extends IOApp {

  // server as defined before
  def server[F[_]: Concurrent](serverSocket: ServerSocket): F[ExitCode] = ???
  
  override def run(args: List[String]): IO[ExitCode] = {
  
    def close[F[_]: Sync](socket: ServerSocket): F[Unit] =
      Sync[F].delay(socket.close()).handleErrorWith(_ => Sync[F].unit)
  
    IO{ new ServerSocket(args.headOption.map(_.toInt).getOrElse(5432)) }
      .bracket{
        serverSocket => server[IO](serverSocket) >> IO.pure(ExitCode.Success)
      } {
        serverSocket => close[IO](serverSocket)  >> IO(println("Server finished"))
      }
  }
}
```

So `run` calls `server` which in turn calls `serve`. Do we need to modify
`serve` as well? Yes, as we need to pass the `stopFlag` to the `echoProtocol`
method:

```scala
import cats.effect._
import cats.effect.ExitCase._
import cats.effect.concurrent.MVar
import cats.effect.syntax.all._
import cats.implicits._
import java.net._

// echoProtocol now requires access to the stopFlag, it will use it to signal the
// server must stop
def echoProtocol[F[_]: Sync](clientSocket: Socket, stopFlag: MVar[F, Unit]): F[Unit] = ???

def serve[F[_]: Concurrent](serverSocket: ServerSocket, stopFlag: MVar[F, Unit]): F[Unit] = {

  def close(socket: Socket): F[Unit] = 
    Sync[F].delay(socket.close()).handleErrorWith(_ => Sync[F].unit)
  
  for {
    _ <- Sync[F]
           .delay(serverSocket.accept())
           .bracketCase { socket =>
             echoProtocol(socket, stopFlag)
               .guarantee(close(socket))                 // Ensuring socket is closed
               .start                                    // Client attended by its own Fiber
           }{ (socket, exit) => exit match {
             case Completed => Sync[F].unit
             case Error(_) | Canceled => close(socket)
           }}
    _ <- serve(serverSocket, stopFlag)                   // Looping back to the beginning
  } yield ()
}
```

There is only one step missing, modifying `echoProtocol`. In fact, the only
relevant changes are on its inner `loop` method. Now it will check whether the
line received from the client is `STOP`, if so it will set the `stopFlag` to
signal the server must be stopped, and the function will quit:

```scala
import cats.effect._
import cats.effect.concurrent.MVar
import cats.effect.syntax.all._
import cats.implicits._
import java.io._

def loop[F[_]:Concurrent](reader: BufferedReader, writer: BufferedWriter, stopFlag: MVar[F, Unit]): F[Unit] =
  for {
    line <- Sync[F].delay(reader.readLine())
    _    <- line match {
              case "STOP" => stopFlag.put(()) // Stopping server! Also put(()) returns F[Unit] which is handy as we are done
              case ""     => Sync[F].unit     // Empty line, we are done
              case _      => Sync[F].delay{ writer.write(line); writer.newLine(); writer.flush() } >> loop(reader, writer, stopFlag)
            }
  } yield ()
```

#### Echo server code, graceful stop version
The code of the server able to react to stop events is available
[here](https://github.com/lrodero/cats-effect-tutorial/blob/master/src/main/scala/catsEffectTutorial/EchoServerV2_GracefulStop.scala).

If you run the server coded above, open a telnet session against it and send an
`STOP` message you will see how the server is properly terminated: the `server`
function will cancel the fiber on `serve` and return, then `bracket` defined in
our main `run` method will finalize the usage stage and relaese the server
socket. This will make the `serverSocket.accept()` in the `serve` function to
throw an exception that will be caught by the `bracketCase` of that function.
Because `serve` was already canceled, and given how we defined the release stage
of its `bracketCase`, the function will finish normally.

#### Exercise: closing client connections to echo server on shutdown
There is a catch yet in our server. If there are several clients connected,
sending a `STOP` message will close the server's fiber and the one attending
the client that sent the message. But the other fibers will keep running
normally! It is like they were daemon threads. Arguably, we could expect that
shutting down the server shall close _all_ connections. How could we do it?
Solving that issue is the proposed exercise below.

We need to close all connections with clients when the server is shut down. To
do that we can call `cancel` on each one of the `Fiber` instances we have
created to attend each new client. But how? After all, we are not tracking
which fibers are running at any given time. We propose this exercise to you: can
you devise a mechanism so all client connections are closed when the server is
shutdown? We outline a solution in the next subsection, but maybe you can
consider taking some time looking for a solution yourself :) .

#### Solution
We could keep a list of active fibers serving client connections. It is
doable, but cumbersome … and not really needed at this point.

Think about it: we have a `stopFlag` that signals when the server must be
stopped. When that flag is set we can assume we shall close all client
connections too. Thus what we need to do is, every time we create a new fiber to
attend some new client, we must also make sure that when `stopFlag` is set that
client is 'shutdown'. As `Fiber` instances are very light we can create a new
instance just to wait for `stopFlag.read` and then forcing the client to stop.
This is how the `serve` method will look like now with that change:

```scala
import cats.effect._
import cats.effect.ExitCase._
import cats.effect.concurrent.MVar
import cats.implicits._
import cats.effect.syntax.all._
import java.net._

def echoProtocol[F[_]: Sync](clientSocket: Socket, stopFlag: MVar[F, Unit]): F[Unit] = ???

def serve[F[_]: Concurrent](serverSocket: ServerSocket, stopFlag: MVar[F, Unit]): F[Unit] = {

  def close(socket: Socket): F[Unit] = 
    Sync[F].delay(socket.close()).handleErrorWith(_ => Sync[F].unit)

  for {
    socket <- Sync[F]
                .delay(serverSocket.accept())
                .bracketCase { socket =>
                  echoProtocol(socket, stopFlag)
                    .guarantee(close(socket))                 // Ensuring socket is closed
                    .start >> Sync[F].pure(socket)            // Client attended by its own Fiber, socket is returned
                }{ (socket, exit) => exit match {
                  case Completed => Sync[F].unit
                  case Error(_) | Canceled => close(socket)
                }}
    _      <- (stopFlag.read >> close(socket)) 
                .start                                        // Another Fiber to cancel the client when stopFlag is set
    _      <- serve(serverSocket, stopFlag)                   // Looping back to the beginning
  } yield ()
}
```

Here we close the client socket once the read on `stopFlag` unblocks. This will
trigger an exception on the `reader.readLine` call. To capture and process the
exception we will use `attempt`, which returns an `Either` instance that will
contain either a `Right[String]` with the line read or a `Left[Throwable]` with
the exception captured. If some error is detected first the state of `stopFlag`
is checked, and if it is set a graceful shutdown is assumed and no action is
taken; otherwise the error is raised:

```scala
import cats.effect._
import cats.effect.concurrent.MVar
import cats.implicits._
import java.io._

def loop[F[_]: Sync](reader: BufferedReader, writer: BufferedWriter, stopFlag: MVar[F, Unit]): F[Unit] =
  for {
    lineE <- Sync[F].delay(reader.readLine()).attempt
    _     <- lineE match {
               case Right(line) => line match {
                 case "STOP" => stopFlag.put(()) // Stopping server! Also put(()) returns F[Unit] which is handy as we are done
                 case ""     => Sync[F].unit     // Empty line, we are done
                 case _      => Sync[F].delay{ writer.write(line); writer.newLine(); writer.flush() } >> loop(reader, writer, stopFlag)
               }
               case Left(e) =>
                 for { // readLine() failed, stopFlag will tell us whether this is a graceful shutdown
                   isEmpty <- stopFlag.isEmpty
                   _       <- if(!isEmpty) Sync[F].unit  // stopFlag is set, cool, we are done
                              else Sync[F].raiseError(e) // stopFlag not set, must raise error
                 } yield ()
             }
  } yield ()
```

Recall that we used `Resource` to instantiate both the `reader` and `writer`
used by `loop`; following how we coded that resource, both that `reader` and
`writer` will be automatically closed.

Now you may think '_wait a minute!, why don't we cancel the client fiber instead
of closing the socket straight away?_' In fact this is perfectly possible, and
it will have a similar effect:

```scala
import cats.effect._
import cats.effect.ExitCase._
import cats.effect.concurrent.MVar
import cats.implicits._
import cats.effect.syntax.all._
import java.net._

def echoProtocol[F[_]: Sync](clientSocket: Socket, stopFlag: MVar[F, Unit]): F[Unit] = ???

def serve[F[_]: Concurrent](serverSocket: ServerSocket, stopFlag: MVar[F, Unit]): F[Unit] = {

  def close(socket: Socket): F[Unit] = 
    Sync[F].delay(socket.close()).handleErrorWith(_ => Sync[F].unit)

  for {
    fiber <- Sync[F]
               .delay(serverSocket.accept())
               .bracketCase { socket =>
                 echoProtocol(socket, stopFlag)
                   .guarantee(close(socket))                 // Ensuring socket is closed
                   .start                                    // Client attended by its own Fiber, which is returned
               }{ (socket, exit) => exit match {
                 case Completed => Sync[F].unit
                 case Error(_) | Canceled => close(socket)
               }}
    _     <- (stopFlag.read >> fiber.cancel) 
               .start                                        // Another Fiber to cancel the client when stopFlag is set
    _     <- serve(serverSocket, stopFlag)                   // Looping back to the beginning
  } yield ()
}
```

What is happening in this latter case? If you take a look again to
`echoProtocol` you will see that the `F` returned by `echoProtocol` is the `F`
given by `Resource.use`. When we cancel the fiber running that `F`, the release
of the resources defined is triggered. That release phase closes the `reader`
and `writer` streams that we created from the client socket... which in turn
closes the client socket! As before, the `attempt` call will take care of the
exception raised. In fact using `cancel` looks cleaner overall. But there is a
catch. The call to `cancel` does not force an `F` to be immediately terminated,
it is not like a `Thread.interrupt`! It happened in our server because it
indirectly created an exception that was raised inside the `F` running the
`reader.readLine`, caused by the socket being closed. If that had not been the
case, the `cancel` call would only have taken effect when the code inside the
`F` running the `reader.readLine` was normally finished. Keep that in mind when
using `cancel` to handle fibers.

#### Echo server code, closing client connections version
The resulting code of this new server, able to shutdown all client connections
on shutdown, is available
[here](https://github.com/lrodero/cats-effect-tutorial/blob/master/src/main/scala/catsEffectTutorial/EchoServerV3_ClosingClientsOnShutdown.scala).

### `Fiber`s are not threads!<a name="fibers-are-not-threads"></a>

As stated before, fibers are like 'light' threads, meaning they can be used in a
similar way than threads to create concurrent code. However, they are _not_
threads. Spawning new fibers does not guarantee that the action described in the
`F` associated to it will be run if there is a shortage of threads. At the end
of the day, if no thread is available that can run the fiber, then the actions
in that fiber will be blocked until some thread is free again.

You can test this yourself. Start the server defined in the previous sections
and try to connect several clients and send lines to the server through them.
Soon you will notice that the latest clients... do not get any echo reply when
sending lines! Why is that?  Well, the answer is that the first fibers already
used all _underlying_ threads available!  But if we close one of the active
clients by sending an empty line (recall that makes the server to close that
client session) then immediately one of the blocked clients will be active.

It shall be clear from that experiment that fibers are run by thread pools. And
that in our case, all our fibers share the same thread pool! `ContextShift[F]`
is in charge of assigning threads to the fibers waiting to be run, each one
with a pending `F` action. When using `IOApp` we get also the `ContextShift[IO]`
that we need to run the fibers in our code. So there are our threads!

### The `ContextShift` type class

Cats-effect provides ways to use different `ContextShift`s (each with its own
thread pool) when running `F` actions, and to swap which one should be used for
each new `F` to ask to reschedule threads among the current active `F`
instances _e.g._ for improved fairness etc. Code below shows an example of how to
declare tasks that will be run in different thread pools: the first task will be
run by the thread pool of the `ExecutionContext` passed as parameter, the second
task will be run in the default thread pool.

```scala
import cats.effect._
import cats.implicits._
import scala.concurrent.ExecutionContext

def doHeavyStuffInADifferentThreadPool[F[_]: ContextShift: Sync](implicit ec: ExecutionContext): F[Unit] =
  for {
    _ <- ContextShift[F].evalOn(ec)(Sync[F].delay(println("Hi!"))) // Swapping to thread pool of given ExecutionContext
    _ <- Sync[F].delay(println("Welcome!")) // Running back in default thread pool
  } yield ()
```

#### Exercise: using a custom thread pool in echo server
Given what we know so far, how could we solve the problem of the limited number
of clients attended in parallel in our echo server? Recall that in traditional
servers we would make use of a specific thread pool for clients, able to resize
itself by creating new threads if they are needed. You can get such a pool using
`Executors.newCachedThreadPool()`. But take care of shutting the pool down when
the server is stopped!

#### Solution
Well, the solution is quite straightforward. We only need to create a thread pool
and execution context, and use it whenever we need to read input from some
connected client. So the beginning of the `echoProtocol` function would look like:

```scala
def echoProtocol[F[_]: Sync: ContextShift](clientSocket: Socket, stopFlag: MVar[F, Unit])(implicit clientsExecutionContext: ExecutionContext): F[Unit] = {

  def loop(reader: BufferedReader, writer: BufferedWriter, stopFlag: MVar[F, Unit]): F[Unit] =
    for {
      lineE <- ContextShift[F].evalOn(clientsExecutionContext)(Sync[F].delay(reader.readLine()).attempt)
//    ...
```

and... that is mostly it. Only pending change is to create the thread pool and
execution context in the `server` function, which will be in charge also of
shutting down the thread pool when the server finishes:

```scala
import cats.effect._
import cats.effect.concurrent.MVar
import cats.effect.syntax.all._
import cats.implicits._
import java.net.ServerSocket
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

def serve[F[_]: Concurrent: ContextShift](serverSocket: ServerSocket, stopFlag: MVar[F, Unit])(implicit clientsExecutionContext: ExecutionContext): F[Unit] = ???

def server[F[_]: Concurrent: ContextShift](serverSocket: ServerSocket): F[ExitCode] = {

  val clientsThreadPool = Executors.newCachedThreadPool()
  implicit val clientsExecutionContext = ExecutionContext.fromExecutor(clientsThreadPool)

  for {
    stopFlag    <- MVar[F].empty[Unit]
    serverFiber <- serve(serverSocket, stopFlag).start         // Server runs on its own Fiber
    _           <- stopFlag.read                               // Blocked until 'stopFlag.put(())' is run
    _           <- Sync[F].delay(clientsThreadPool.shutdown()) // Shutting down clients pool
    _           <- serverFiber.cancel.start                    // Stopping server
  } yield ExitCode.Success
}
```

Signatures of `serve` and of `echoProtocol` will have to be changed too to pass
the execution context as parameter. Finally, we need an implicit
`ContextShift[F]` that will be carried by the function's signature. It is `IOApp`
which provides the instance of `ContextShift[IO]` in the `run` method.

#### Echo server code, thread pool for clients version
The version of our echo server using a thread pool is available
[here](https://github.com/lrodero/cats-effect-tutorial/blob/master/src/main/scala/catsEffectTutorial/EchoServerV4_ClientThreadPool.scala).

## Let's not forget about `async`

The `async` functionality is another powerful capability of cats-effect we have
not mentioned yet. It is provided by `Async` type class, and it allows to
describe `F` instances that may be terminated by a thread different than the
one carrying the evaluation of that instance. The result will be returned by
using a callback.

Some of you may wonder if that could help us to solve the issue of having
blocking code in our fabulous echo server. Unfortunately, `async` cannot
magically 'unblock' such code. Try this simple code snippet (_e.g._ in `sbt`
console):

```scala
import cats.effect._
import cats.effect.syntax.all._
import cats.implicits._
import scala.util.Either

def delayed[F[_]: Async]: F[Unit] = for {
  _ <- Sync[F].delay(println("Starting")) // Async extends Sync, so (F[_]: Async) 'brings' (F[_]: Sync)
  _ <- Async[F].async{ (cb: Either[Throwable,Unit] => Unit) =>
      Thread.sleep(2000)
      cb(Right(()))
    }
  _ <- Sync[F].delay(println("Done")) // 2 seconds to get here, no matter what, as we were 'blocked' by previous call
} yield()

delayed[IO].unsafeRunSync() // a way to run an IO without IOApp
```

You will notice that the code above still blocks, waiting for the `async` call
to finish.

### Using `async` in our echo server
So how is `async` useful? Well, let's see how we can apply it on our server
code. Because `async` allows a different thread to finish the task, we can
modify the blocking read call inside the `loop` function of our server with
something like:

```scala
for {
  lineE <- Async[F].async{ (cb: Either[Throwable, Either[Throwable, String]] => Unit) => 
             clientsExecutionContext.execute(new Runnable {
               override def run(): Unit = {
                 val result: Either[Throwable, String] = Try(reader.readLine()).toEither
                 cb(Right(result))
               }
             })
           }
// ...           
```

Note that the call `clientsExecutionContext.execute` will create a thread from
that execution context, setting free the thread that was evaluating the `F`
for-comprehension. If the thread pool used by the execution context can create
new threads if no free ones are available, then we will be able to attend as
many clients as needed. This is similar to the solution we used previously when
we asked to run the blocking `readLine` call in a different execution context.
The final result will be identical to our previous server version. To attend
client connections, if no thread is available in the pool, new threads will be
created from that pool.

#### Echo server code, async version
A full version of our echo server using this async approach is available
[here](https://github.com/lrodero/cats-effect-tutorial/blob/master/src/main/scala/catsEffectTutorial/EchoServerV5_Async.scala).

### When is `async` useful then?
The `Async` type class is useful specially when the task to run by the `F` can
be terminated by any thread. For example, calls to remote services are often
modeled with `Future`s so they do not block the calling thread. When defining
our `F`, should we block on the `Future` waiting for the result? No! We can
wrap the call in an `async` call like:

```scala
import cats.effect._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util._

trait Service { def getResult(): Future[String] }
def service: Service = ???

def processServiceResult[F[_]: Async] = Async[F].async{ (cb: Either[Throwable, String] => Unit) => 
  service.getResult().onComplete {
    case Success(s) => cb(Right(s))
    case Failure(e) => cb(Left(e))
  }
}
```

So, let's say our new goal is to create an echo server that does not require a
thread per connected socket to wait on the blocking `read()` method. If we use a
network library that avoids blocking operations, we could then combine that with
`async` to create such non-blocking server. And Java NIO can be helpful here.
While Java NIO does have some blocking method (`Selector`'s `select()`), it
allows to build servers that do not require a thread per connected client:
`select()` will return those 'channels' (such as `SocketChannel`) that have data
available to read from, then processing of the incoming data can be split among
threads of a size-bounded pool. This way, a thread-per-client approach is not
needed. Java NIO2 or netty could also be applicable to this scenario. We leave
as a final exercise to implement again our echo server but this time using an
async lib.

## Conclusion

With all this we have covered a good deal of what cats-effect has to offer (but
not all!). Now you are ready to use to create code that operate side effects in
a purely functional manner. Enjoy the ride!
