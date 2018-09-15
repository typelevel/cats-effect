---
layout: docsplus
title:  "Tutorial"
position: 2
---

<nav role="navigation" id="toc"></nav>

## Introduction

This tutorial tries to help newcomers to cats-effect to get familiar with its
main concepts by means of code examples. The first one shows how we can use
cats-effect to copy the contents from one file to another. That should help us
to flex our muscles. The second one is a bit more elaborated. Step by step, we
will build a light TCP server whose complexity will grow as we introduce more
features. That growing complexity will help us to introduce more and more
concepts from cats-effect.

That said, let's go!

## Setting things up

The [Github repo for this
tutorial](https://github.com/lrodero/cats-effect-tutorial) includes all the
software that will be developed during this tutorial. It uses `sbt` as the build
tool. To ease coding, compiling and running the code snippets in this tutorial
it is recommended to use the same `build.sbt`, or at least one with the same
dependencies and compilation options:

```scala
name := "cats-effect-tutorial"

version := "1.0"

scalaVersion := "2.12.2"

libraryDependencies += "org.typelevel" %% "cats-effect" % "1.0.0" withSources() withJavadoc()

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps",
  "-language:higherKinds",
  "-Ypartial-unification")
```

## Warming up: Copying contents of a file

First we will code a function that copies the content from a file to another
file. The function takes as parameters the source and destination files. But
this is functional programming! So invoking the function will not copy anything,
instead it will return an `IO` instance that encapsulates all the side-effects
involved (opening/closing files, copying content), that way _purity_ is kept.
Only when that `IO` instance is evaluated all those side-effectul actions will
be run. In our implementation the `IO` instance will return the amount of bytes
copied upon execution, but this is just a design decission.

Now, the signature of our function looks like this:

```scala
import cats.effect.IO
import java.io.File

def copy(origin: File, destination: File): IO[Long] = ???
```

Nothing scary, uh? As we said before, the function just returns an `IO`
instance. When run, all side-effects will be actually executed and the `IO`
instance will return the bytes copies in a `Long`. Note that`IO` is
parameterized by the return type). Now, let's start implementing our funtion.
First, we need to open two streams that will read and write file contents. 

### Acquiring and releasing `Resource`s
We consider opening an stream to be a side-effect action, so we have to
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

We want to ensure that once we are done using each stream is closed, no matter
what. That is precisely why we use `Resource` in both `inputStream` and
`outputStream` functions, each one returning one `Resource` that encapsulates
the actions for opening and then closing each stream.  `inputOutputStreams`
encapsulates both resources in a single `Resource` instance that will be
available once the creation has been successful. As seen in the code above
`Resource` instances can be combined in for-comprehensions as they implement
`flatMap`. Note also that when releasing resources we also take care of any
possible error during the release itself. In this case we just 'swallow' the
error, but normally it would be at least logged.

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
logs. Those can be easily added to the release phase of a 'typical' `Resource`
definition.

Let's go back to our `copy` function, which now looks like this:

```scala
import cats.effect.{IO, Resource}
import java.io._

// as defined before
def inputOutputStreams(in: File, out: File): Resource[IO, (InputStream, OutputStream)] = ???

// transfer will do the real work
def transfer(origin: InputStream, destination: OutputStream): IO[Long] = ???

def copy(origin: File, destination: File): IO[Long] = 
  for {
    count <- inputOutputStreams(origin, destination).use { case (in, out) => 
               transfer(in, out)
             }
  } yield count
```

The new method `transfer` will perform the actual copying of data, once the
resources (the streams) are obtained. When they are not needed anymore, whatever
the outcome of `transfer` (it run successfully, there was an exception...) both
streams will be closed. If any of the streams could not be obtained, then
`transfer` will not be run. Even better, because of `Resource` semantics, if
there is any problem opening the input file then the output file will not be
opened.  On the other hand, if there is any issue opening the output file, then
the input stream will be closed.

### What about `bracket`?
Now, if you are familiar with cats-effect's `bracket` you can be wondering why
we are not using it as it looks so similar to `Resource`. There are three stages
when using `bracket`: _resource adquisition_, _usage_, and _release_. Each stage
is defined by an `IO` instance.  A fundamental property is that the _release_
stage will always be run regardless whether the _usage_ stage finished correctly
or an exception was thrown during its execution. In our case, in the
_adquisition_ stage we would create the streams, then in the _usage_ stage we
will copy the contents, and finally in the release stage we will close the
streams.  Thus we could define our `copy` function as follows:

```scala
import cats.effect.IO
import cats.implicits._ 
import java.io._ 

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
        .handleErrorWith(_ => IO.unit) *> IO.unit
    }
}
```

Code is way shorter! But there is a catch in the code above.  When using
`bracket`, if there is a problem when getting resources in the first stage, then
the release stage will not be run. Now, in the code above first the origin file
and then the destination file are opened (`tupled` just reorganizes both `IO`
instances in a single one). So what would happen if we successfully open the
origin file (_i.e._ when evaluating `inIO`) but then an exception is raised when
opening the destination file (_i.e._ when evaluating `outIO`)? In that case the
origin stream will not be closed!

Still, keep in mind there is a reason why `Resource` and `bracket` look so
similar. In fact, `Resource` instances are using `bracket` underneath. But as
commented before `Resource` allows for a more orderly handling of resources.

### Copying data 
Now we do have our streams ready to go! We have to focus now on coding
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
    count  <- if(amount > -1) IO(destination.write(buffer, 0, amount)) *> transmit(origin, destination, buffer, acc + amount)
              else IO.pure(acc) // End of read stream reached (by java.io.InputStream contract), nothing to write
  } yield count // Returns the actual amount of bytes transmitted

def transfer(origin: InputStream, destination: OutputStream): IO[Long] =
  for {
    buffer <- IO(new Array[Byte](1024 * 10)) // Allocated only when the IO is evaluated
    total  <- transmit(origin, destination, buffer, 0L)
  } yield total
```

Take a look to `transmit`, observe that both input and output actions are
encapsulated in their own `IO` instances. Being `IO` a monad, we can sequence
them using a for-comprehension to create another `IO`. The for-comprehension
loops as long as the call to `read()` does not return a negative value, by means
of recursive calls. But `IO` is stack safe, so we are not concerned about stack
overflow issues. At each iteration we increase the counter `acc` with the amount
of bytes read at that iteration. 

We are making progress, and already have a version of `copy` that can be used.
If any exception is raised when `transfer` is running, then the streams will be
automatically closed by `Resource`. But there is something else we have to take
into account: `IO` instances execution can be **_cancelled!_**. And cancellation
should not be ignored, as it is a key feature of cats-effect. We will discuss
cancellation in the next section.

### Dealing with cancellation
Cancellation is a cats-effect feature, powerful but non trivial. In cats-effect,
some `IO` instances can be cancelable, meaning than their evaluation will be
aborted. If the programmer is careful, an alternative `IO` task will be run
under cancellation, for example to deal with potential cleaning up activities.
We will see how an `IO` can be actually cancelled at the end of the [Fibers are
not threads!  section](#fibers-are-not-threads), but for now we will just keep
in mind that during the execution of the `transfer` method a cancellation can
occur at any time.

Now, `IO`s created with `Resource.use` can be cancelled. The cancellation will
trigger the execution of the code that handles the closing of the resource. In
our case, that would close both streams. So far so good! But what happens if
cancellations happens _while_ the streams are being used, _i.e._ the `transfer`
method is being run? This could lead to data corruption as a stream where some
thread is writting to is at the same time being closed by another thread. For
more info about this problem see [Gotcha: Cancellation is a concurrent
action](https://typelevel.org/cats-effect/datatypes/io.html#gotcha-cancellation-is-a-concurrent-action)
in cats-effect site.

To prevent such data corruption we must use some concurrency control mechanism
that ensures that no stream will be closed while `transfer` is being evaluated.
Cats effect provides several constructs for controlling concurrency, for this
case we will use a
[_semaphore_](https://typelevel.org/cats-effect/concurrency/semaphore.html). A
semaphore has a number of permits, its method `adquire` blocks if no permit is
available until `release` is called on the same semaphore. We will use a
semaphore with a single permit, along with a new function `close` that will
close the stream, defined outside `copy` for the sake of readability:

```scala
import cats.implicits._
import cats.effect.{IO, Resource}
import cats.effect.concurrent.Semaphore
import java.io._
import scala.concurrent.ExecutionContext.Implicits.global

// (transfer and transmit methods are not changed)
def transfer(origin: InputStream, destination: OutputStream): IO[Long] = ???

def inputStream(f: File, guard: Semaphore[IO]): Resource[IO, FileInputStream] =
  Resource.make {
    IO(new FileInputStream(f))
  } { inStream => 
    for  {
     _ <- guard.acquire
     _ <- IO(inStream.close())
     _ <- guard.release
    } yield ()
  }

def outputStream(f: File, guard: Semaphore[IO]): Resource[IO, FileOutputStream] =
  Resource.make {
    IO(new FileOutputStream(f))
  } { outStream =>
    for  {
     _ <- guard.acquire
     _ <- IO(outStream.close())
     _ <- guard.release
    } yield ()
  }

def inputOutputStreams(in: File, out: File, guard: Semaphore[IO]): Resource[IO, (InputStream, OutputStream)] =
  for {
    inStream  <- inputStream(in, guard)
    outStream <- outputStream(out, guard)
  } yield (inStream, outStream)

def copy(origin: File, destination: File): IO[Long] = {
  implicit val contextShift = IO.contextShift(global)
  for {
    guard <- Semaphore[IO](1)
    count <- inputOutputStreams(origin, destination, guard).use { case (in, out) => 
               guard.acquire *> transfer(in, out).guarantee(guard.release)
             }
  } yield count
}
```

Before calling to `transfer` we acquire the semaphore, which is not released
until `transfer` is done. The `guarantee` call ensures that the semaphore will
be released under any circumstance, whatever the result of `transfer` (success,
error, or cancellation). As the 'release' parts in the `Resource` instances are
now blocked on the same semaphore, we can be sure that streams are closed only
after `transfer` is over, _i.e._ we have implemented mutual exclusion of those
functionalities.

And that is it! We are done, now we can create a program that uses this
function.

### `IOApp` for our final program

We will create a program that copies files, this program only takes two
parameters: the name of the origin and destination files. For coding this
program we will use `IOApp` as it allows to maintain purity in our definitions
up to the main function. You can check the final result
[here](https://github.com/lrodero/cats-effect-tutorial/blob/master/src/main/scala/catsEffectTutorial/CopyFile.scala).

`IOApp` is a kind of 'functional' equivalent to Scala's `App`, where instead of
coding an effectful `main` method we code a pure `run` function. When executing
the class a `main` method defined in `IOApp` will call the `run` function we
have coded. Any interruption (like pressing `Ctrl-c`) will be treated as a
cancellation of the running `IO`. Also `IOApp` provides an implicit execution
context so it does not be imported/created by the code explicitely.

Also, heed in the example linked above how `run` verifies the `args` list
passed. If there are less than two arguments, an error is raised. As `IO`
implements `MonadError` we can at any moment call to `IO.raiseError` to
interrupt a sequence of `IO` operations.

You can run this code from `sbt` just by issuing this call:

```scala
> runMain catsEffectTutorial.CopyFile origin.txt destination.txt
```

It can be argued than using `IO{java.nio.file.Files.copy(...)}` would get an
`IO` with the same characteristics of purity than our function. But there is a
difference: our `IO` is safely cancelable! So the user can stop the running
code at any time for example by pressing `Ctrl-c`, our code will deal with safe
resource release (streams closing) even under such circumstances. The same will
apply if the `copy` function is run from other modules that require its
functionality. If the function is cancelled while being run, still resources
will be properly released.

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

## A concurrent system with `Fiber`s: echo server

This example is a bit more complex. Here we create an echo TCP server that
simply replies to each text message from a client sending back that same
message. When the client sends an empty line its connection is shutdown by the
server. This server will be able to attend several clients at the same time. For
that we will use `cats-effect`'s `Fiber`, which can be seen as light threads.
For each new client a `Fiber` instance will be spawned to serve that client.

We will stick to a simple design principle: _whoever method creates a resource
is the sole responsible of dispatching it!_.  It's worth to remark this from the
beginning to better understand the code listings shown in this tutorial.

Let's build our server step-by-step. First we will code a method that implements
the echo protocol. It will take as input the socket (`java.net.Socket` instance)
that is connected to the client. The method will be basically a loop that at
each iteration reads the input from the client, if the input is not an empty
line then the text is sent back to the client, otherwise the method will finish.

The method signature will look like this:

```scala
import cats.effect.IO
import java.net.Socket
def echoProtocol(clientSocket: Socket): IO[Unit] = ???
```

Reading and writing will be done using `java.io.BufferedReader` and
`java.io.BufferedWriter` instances build from the socket. Recall that this
method will be in charge of closing those buffers, but not the client socket (it
did not create that socket after all!). We will use again `Resource` to ensure
that we close the streams we create. Also, all actions with potential
side-effects are encapsulated in `IO` instances. That way we ensure no
side-effect is actually run until the `IO` returned by this method is evaluated.
With this in mind, code looks like:

```scala
import cats.effect._
import cats.implicits._
import java.io._
import java.net._

def echoProtocol(clientSocket: Socket): IO[Unit] = {

  def loop(reader: BufferedReader, writer: BufferedWriter): IO[Unit] = for {
    line <- IO(reader.readLine())
    _    <- line match {
              case "" => IO.unit // Empty line, we are done
              case _  => IO{ writer.write(line); writer.newLine(); writer.flush() } *> loop(reader, writer)
            }
  } yield ()

  def reader(clientSocket: Socket): Resource[IO, BufferedReader] =
    Resource.make {
      IO( new BufferedReader(new InputStreamReader(clientSocket.getInputStream())) )
    } { reader =>
      IO(reader.close()).handleErrorWith(_ => IO.unit)
    }

  def writer(clientSocket: Socket): Resource[IO, BufferedWriter] =
    Resource.make {
      IO( new BufferedWriter(new PrintWriter(clientSocket.getOutputStream())) )
    } { writer =>
      IO(writer.close()).handleErrorWith(_ => IO.unit)
    }

  def readerWriter(clientSocket: Socket): Resource[IO, (BufferedReader, BufferedWriter)] =
    for {
      reader <- reader(clientSocket)
      writer <- writer(clientSocket)
    } yield (reader, writer)

  readerWriter(clientSocket).use { case (reader, writer) =>
    loop(reader, writer) // Let's get to work
  }

}
```

Note that, as we did in the previous example, we ignore possible errors when
closing the streams, as there is little to do in such cases.

Of course, we still miss that `loop` method that will do the actual interactions
with the client. It is not hard to code though:

```scala
import cats.effect.IO
import cats.implicits._
import java.io._

def loop(reader: BufferedReader, writer: BufferedWriter): IO[Unit] =
  for {
    line <- IO(reader.readLine())
    _    <- line match {
              case "" => IO.unit // Empty line, we are done
              case _  => IO{ writer.write(line); writer.newLine(); writer.flush() } *> loop(reader, writer)
            }
  } yield ()
```

The loop tries to read a line from the client, and if successful then it checks
the line content. If empty it finishes the method, if not it sends back the
line through the writer and loops back to the beginning. Easy, right :) ?

So we are done with our `echoProtocol` method, good! But we still miss the part
of our server that will list for new connections and create fibers to attend
them. Let's work on that, we implement that functionality in another method
that takes as input the `java.io.ServerSocket` instance that will listen for
clients:

```scala
import cats.effect.IO
import java.net.{ServerSocket, Socket}
import scala.concurrent.ExecutionContext.Implicits.global

// echoProtocol as defined before
def echoProtocol(clientSocket: Socket): IO[Unit] = ???

def serve(serverSocket: ServerSocket): IO[Unit] = {
  def close(socket: Socket): IO[Unit] = 
    IO(socket.close()).handleErrorWith(_ => IO.unit)

  implicit val contextShift = IO.contextShift(global)
  for {
    socket <- IO(serverSocket.accept())
    _      <- echoProtocol(socket)
                .guarantee(close(socket)) // We close the socket whatever happens
                .start                    // Client attended by its own Fiber!
    _      <- serve(serverSocket)         // Looping back to the beginning
  } yield ()
}
```

To be honest, that wasn't that hard either, was it? We invoke the `accept()`
method of `ServerSocket` and when a new connection arrives we call the
`echoProtocol` method we defined above to attend it. As client socket instances
are created by this method, then it is in charge of closing them once
`echoProtocol` has finished. We do this with the quite handy `guarantee` call,
that ensures that when the `IO` finishes the functionality inside `guarantee`
is run whatever the outcome was. In this case we ensure closing the socket,
ignoring any possible error when closing. Also quite interesting: we use
`start`! By doing so the `echoProtocol` call will run on its own fiber thus
not blocking the main loop.

However let's not mix up this with Java thread `interrupt` or the like. Calling
to `cancel` on a `Fiber` instance will not stop it immediately.  Thing is, a
`cancel` call can only have effect when an `IO` is evaluated. In this case the
`IO` is blocked on `accept()`, until that call returns and the next `IO` is
evaluated (next line in the for-comprehension in the code example above)
cats-effect will not be able to 'abandon' the execution. So in the code above,
if the fiber is waiting on `accept()` then `cancel()` would not 'unlock' the
fiber. Instead the fiber will keep waiting for a connection.

_NOTE: If you have coded servers before, probably you are wondering if
cats-effect provides some magical way to attend an unlimited number of clients
without balancing the load somehow. Truth is, it doesn't. You can spawn as many
fibers as you wish, but there is no guarantee they will run simultaneously. More
about this in the section [Fibers are not threads!](#fibers-are-not-threads)_

So, what do we miss now? Only the creation of the server socket of course,
which we can already do in the `run` method of an `IOApp`:

```scala
import cats.effect.{ExitCode, IO}
import cats.implicits._
import java.net.ServerSocket

/// serve as defined before
def serve(serverSocket: ServerSocket): IO[Unit] = ???

def run(args: List[String]): IO[ExitCode] = {

  def close(socket: ServerSocket): IO[Unit] =
    IO(socket.close()).handleErrorWith(_ => IO.unit)

  IO( new ServerSocket(args.headOption.map(_.toInt).getOrElse(5432)) )
    .bracket{
      serverSocket => serve(serverSocket) *> IO.pure(ExitCode.Success)
    } {
      serverSocket => close(serverSocket) *> IO(println("Server finished"))
    }
}
```

Our server now looks [like
this](https://github.com/lrodero/cats-effect-tutorial/blob/master/src/main/scala/catsEffectTutorial/EchoServerV1_Simple.scala).

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
our server can attend all of them simultaneously.

Unfortunately this server is a bit too simplistic. For example, how can we stop
it? Well, that is something we have not addressed yet and it is when things can
get a bit more complicated. We will deal with proper server halting in the next
section.


## Graceful server stop (handling exit events)

There is no way to shutdown gracefully the echo server coded in the previous
version. Sure we can always `Ctrl-c` it, but proper servers should provide
better mechanisms to stop them. In this section we use some other `cats-effect`
constructs to deal with this.

First, we will use a flag to signal when the server shall quit. The server will
run on its own fiber, that will be cancelled when that flag is set. The flag
will be an instance of `MVar`. The `cats-effect` documentation describes `MVar`
as _a mutable location that can be empty or contains a value, asynchronously
blocking reads when empty and blocking writes when full_. That is what we need,
the ability to block! So we will 'block' by reading our `MVar` instance, and
only writing when `STOP` is received, the write being the _signal_ that the
server must be shut down. The server will be only stopped once, so we are not
concerned about blocking on writing. Another possible choice would be using
`cats-effect`'s `Deferred`, but unlike `Deferred` `MVar` does allow to peek
whether a value was written or not. As we shall see, this will come handy later
on.

And who shall signal that the server must be stopped? In this example, we will
assume that it will be the connected users who can request the server to halt by
sendind a `STOP` message. Thus, the method attending clients (`echoProtocol`!)
needs access to the flag.

Let's first define a new method `server` that instantiates the flag, runs the
`serve` method in its own fiber and waits on the flag to be set. Only when
the flag is set the server fiber will be cancelled.

```scala
import cats.effect.{ExitCode, IO}
import cats.effect.concurrent.MVar
import java.net.ServerSocket
import scala.concurrent.ExecutionContext.Implicits.global

// serve now requires access to the stopFlag, it will use it to signal the
// server must stop
def serve(serverSocket: ServerSocket, stopFlag: MVar[IO, Unit]): IO[Unit] = ???

def server(serverSocket: ServerSocket): IO[ExitCode] = {
  implicit val contextShift = IO.contextShift(global)
  for {
      stopFlag    <- MVar[IO].empty[Unit]
      serverFiber <- serve(serverSocket, stopFlag).start // Server runs on its own Fiber
      _           <- stopFlag.read                       // Blocked until 'stopFlag.put(())' is run
      _           <- serverFiber.cancel                  // Stopping server!
  } yield ExitCode.Success
}
```

The code above requires a `contextShift` in scope to compile. In the final version our
server will run inside an `IOApp` and it will not be necessary to pass it
explicitly, `IOApp` will take care of that.

We must also modify the main `run` method in `IOApp` so now it calls to `server`:

```scala
import cats.effect._
import cats.implicits._
import java.net.ServerSocket

// server as defined before
def server(serverSocket: ServerSocket): IO[ExitCode] = ???

def run(args: List[String]): IO[ExitCode] = {

  def close(socket: ServerSocket): IO[Unit] =
    IO(socket.close()).handleErrorWith(_ => IO.unit)

  IO{ new ServerSocket(args.headOption.map(_.toInt).getOrElse(5432)) }
    .bracket{
      serverSocket => server(serverSocket) *> IO.pure(ExitCode.Success)
    } {
      serverSocket => close(serverSocket)  *> IO(println("Server finished"))
    }
}
```

So `run` calls `server` which in turn calls `serve`. Do we need to modify
`serve` as well? Yes, for two reasons:

1. We need to pass the `stopFlag` to the `echoProtocol` method.
2. When the `server` method returns the `run` method will close the server
   socket. That will cause the `serverSocket.accept()` in `serve` to throw an
   exception. We could handle it as any other exception... but that would show
   an error message in console, while in fact this is a 'controlled' shutdown.
   So we should instead control what caused the exception.

This is how we implement `serve` now:

```scala
import cats.effect.IO
import cats.effect.concurrent.MVar
import cats.implicits._
import java.net._
import scala.concurrent.ExecutionContext.Implicits.global

// echoProtocol now requires access to the stopFlag, it will use it to signal the
// server must stop
def echoProtocol(clientSocket: Socket, stopFlag: MVar[IO, Unit]): IO[Unit] = ???

def serve(serverSocket: ServerSocket, stopFlag: MVar[IO, Unit]): IO[Unit] = {

  def close(socket: Socket): IO[Unit] = 
    IO(socket.close()).handleErrorWith(_ => IO.unit)

  implicit val contextShift = IO.contextShift(global)
  for {
    socketE <- IO(serverSocket.accept()).attempt
    _       <- socketE match {
      case Right(socket) =>
        for { // accept() succeeded, we attend the client in its own Fiber
          _ <- echoProtocol(socket, stopFlag)
                 .guarantee(close(socket))   // We close the server whatever happens
                 .start                      // Client attended by its own Fiber
          _ <- serve(serverSocket, stopFlag) // Looping back to the beginning
        } yield ()
      case Left(e) =>
        for { // accept() failed, stopFlag will tell us whether this is a graceful shutdown
          isEmpty <- stopFlag.isEmpty
          _       <- if(!isEmpty) IO.unit    // stopFlag is set, nothing to do
                     else IO.raiseError(e)   // stopFlag not set, must raise error
        } yield ()
    }
  } yield ()
}
```

This new implementation of `serve` does not just call `accept()` inside an `IO`.
It also uses `attempt`, which returns an instance of `Either`
(`Either[Throwable, Socket]` in this case). We can then check the value of that
`Either` to verify if the `accept()` call was successful, and in case of error
deciding what to do depending on whether the `stopFlag` is set or not. If it is
set then we assume that the socket was closed due to normal cancellation (_i.e._
`server` called `cancel` on the fiber running `serve`). If not the error is
promoted using `IO.raiseError`, which will also quit the current `IO` execution.

There is only one step missing, modifying `echoProtocol`. In fact, the only
relevant changes are on its inner `loop` method. Now it will check whether the
line received from the client is "`STOP`", if so it will set the `stopFlag` to
signal the server must be stopped, and the function will quit:

```scala
import cats.effect.IO
import cats.effect.concurrent.MVar
import cats.implicits._
import java.io._

def loop(reader: BufferedReader, writer: BufferedWriter, stopFlag: MVar[IO, Unit]): IO[Unit] =
  for {
    line <- IO(reader.readLine())
    _    <- line match {
              case "STOP" => stopFlag.put(()) // Stopping server! Also put(()) returns IO[Unit] which is handy as we are done
              case ""     => IO.unit          // Empty line, we are done
              case _      => IO{ writer.write(line); writer.newLine(); writer.flush() } *> loop(reader, writer, stopFlag)
            }
  } yield ()
```

Code now looks [like
this](https://github.com/lrodero/cats-effect-tutorial/blob/master/src/main/scala/catsEffectTutorial/EchoServerV2_GracefulStop.scala).

If you run the server coded above, open a telnet session against it and send an
`STOP` message you will see how the server finishes properly.

But there is a catch yet. If there are several clients connected, sending an
`STOP` message will close the server's fiber and the one attending the client
that sent the message. But the other fibers will keep running normally! It is
like they were daemon threads. Arguably, we could expect that shutting down the
server shall close _all_ connections. How could we do it? Solving that issue is
the proposed final exercise below.

### Exercise: closing client connections to echo server on shutdown
We need to close all connections with clients when the server is shut down. To
do that we can call `cancel` on each one of the `Fiber` instances we have
created to attend each new client. But how? After all, we are not tracking
which fibers are running at any given time. We propose this exercise to you: can
you devise a mechanism so all client connections are closed when the server is
shutdown? We propose a solution in the next subsection, but maybe you can
consider taking some time looking for a solution yourself :) .

### Solution
We could keep a list of active fibers serving client connections. It is
doable, but cumbersome...  and not really needed at this point.

Think about it: we have a `stopFlag` that signals when the server must be
stopped. When that flag is set we can assume we shall close all client
connections too. Thus  what we need to do is, every time we create a new
fiber to attend some new client, we must also make sure that when `stopFlag`
is set that client is 'shutdown'. As `Fiber` instances are very light we can
create a new instance just to wait for `stopFlag.read` and then forcing the
client to stop. This is how the `serve` method will look like now with
that change:

```scala
import cats.effect.IO
import cats.effect.concurrent.MVar
import cats.implicits._
import java.net._
import scala.concurrent.ExecutionContext.Implicits.global

def echoProtocol(clientSocket: Socket, stopFlag: MVar[IO, Unit]): IO[Unit] = ???

def serve(serverSocket: ServerSocket, stopFlag: MVar[IO, Unit]): IO[Unit] = {

  def close(socket: Socket): IO[Unit] = 
    IO(socket.close()).handleErrorWith(_ => IO.unit)

  implicit val contextShift = IO.contextShift(global)
  for {
    socketE <- IO(serverSocket.accept()).attempt
    _       <- socketE match {
      case Right(socket) =>
        for { // accept() succeeded, we attend the client in its own Fiber
          fiber <- echoProtocol(socket, stopFlag)
                     .guarantee(close(socket))      // We close the server whatever happens
                     .start                         // Client attended by its own Fiber
          _     <- (stopFlag.read *> close(socket)) 
                     .start                         // Another Fiber to cancel the client when stopFlag is set
          _     <- serve(serverSocket, stopFlag)    // Looping to wait for the next client connection
        } yield ()
      case Left(e) =>
        for { // accept() failed, stopFlag will tell us whether this is a graceful shutdown
          isEmpty <- stopFlag.isEmpty
          _       <- if(!isEmpty) IO.unit  // stopFlag is set, cool, we are done
                     else IO.raiseError(e) // stopFlag not set, must raise error
        } yield ()
    }
  } yield ()
}
```

Now you may think '_wait a minute!, why don't cancelling the client fiber
instead of closing the socket straight away?_'. Well, cancellation will not have
effect until some new `IO` instance is evaluated. Normally the loop will be
blocked on the `IO` instance that contains the `reader.readLine()` call. Until
that `IO` operation is done the cancellation will not take effect. But then
again, we want the connection to the client to be closed right away.  To force
the termination even when the reader is waiting for input data we close the
client socket. That will raise an exception in the `reader.readLine()` line.  As
it happened before with the server socket, that exception will be shown as an
ugly error message in the console. To prevent this we modify the `loop` function
so it uses `attempt` to control possible errors. If some error is detected first
the state of `stopFlag` is checked, and if it is set a graceful shutdown is
assumed and no action is taken; otherwise the error is raised:

```scala
import cats.effect.IO
import cats.effect.concurrent.MVar
import cats.implicits._
import java.io._

def loop(reader: BufferedReader, writer: BufferedWriter, stopFlag: MVar[IO, Unit]): IO[Unit] =
  for {
    lineE <- IO(reader.readLine()).attempt
    _     <- lineE match {
               case Right(line) => line match {
                 case "STOP" => stopFlag.put(()) // Stopping server! Also put(()) returns IO[Unit] which is handy as we are done
                 case ""     => IO.unit          // Empty line, we are done
                 case _      => IO{ writer.write(line); writer.newLine(); writer.flush() } *> loop(reader, writer, stopFlag)
               }
               case Left(e) =>
                 for { // readLine() failed, stopFlag will tell us whether this is a graceful shutdown
                   isEmpty <- stopFlag.isEmpty
                   _       <- if(!isEmpty) IO.unit  // stopFlag is set, cool, we are done
                              else IO.raiseError(e) // stopFlag not set, must raise error
                 } yield ()
             }
  } yield ()
```

The resulting server code is [also
available](https://github.com/lrodero/cats-effect-tutorial/blob/master/src/main/scala/catsEffectTutorial/EchoServerV3_ClosingClientsOnShutdown).

## `Fiber`s are not threads!<a name="fibers-are-not-threads"></a>

As stated before, fibers are like 'light' threads, meaning they can be used in a
similar way than threads to create concurrent code. However, they are _not_
threads. Spawning new fibers does not guarantee that the action described in the
`IO` associated to it will be run... immediately. At the end of the day, if no
thread is available that can run the fiber, then the actions in that fiber will
be blocked until some thread is available.

You can test this yourself. Start the server defined in the previous sections
and try to connect several clients and send lines to the server through them.
Soon you will notice that the latest clients... do not get any echo reply when
sending lines! Why is that?  Well, the answer is that the first fibers already
used all _underlying_ threads available!  But if we close one of the active
clients by sending an empty line (recall that makes the server to close that
client session) then immediately one of the blocked clients will be active.

It shall be clear from that experiment than fibers are run by thread pools. And
that in our case, all our fibers share the same thread pool!  Which one in our
case? Well, `IOApp` automatically brings a `Timer[IO]`, that is defined by
cats-effect as a '_functional equivalente of Java's
[ScheduledExecutorService](https://docs.oracle.com/javase/9/docs/api/java/util/concurrent/ScheduledExecutorService.html)_'.
Each executor service has an underlying thread pool to execute the commands it
is requested, and the same applies to `Timer`, which is in charge of assigning
available threads to the pending `IO` actions. So there are our threads!

Cats-effect provides ways to use different `scala.concurrent.ExecutionContext`s,
each one with its own thread pool, and to swap which one should be used for each
new `IO` to ask to reschedule threads among the current active `IO` instances
_e.g._ for improved fairness etc. Such functionality is provided by `IO.shift`.
Also, it even allows to 'swap' to different `Timer`s (that is, different thread
pools) when running `IO` actions. See this code, which is mostly a copy example
available at [IO documentation in cats-effect web, Thread Shifting
section](https://typelevel.org/cats-effect/datatypes/io.html#thread-shifting):

```scala
import cats.effect.IO
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global

val cachedThreadPool = Executors.newCachedThreadPool()
val clientsExecutionContext = ExecutionContext.fromExecutor(cachedThreadPool)
implicit val contextShift = IO.contextShift(global) 

for {
  _ <- IO.shift(clientsExecutionContext) // Swapping to cached thread pool
  _ <- IO(???) // Whatever is done here, is done by a thread from the cached thread pool
  _ <- IO.shift // Swapping back to default timer
  _ <- IO(println(s"Welcome!")) 
} yield ()
```

`IO.shift` is a powerful tool but can be a bit confusing to beginners (and even
intermediate) users of cats-effect. So do not worry if it takes some time for
you to master.

### Exercise: using a custom thread pool in echo server
Given what we know so far, how could we solve the problem of the limited number
of clients attended in parallel in our echo server? Recall that in traditional
servers we would make use of an specific thread pool for clients, able to resize
in case more threads are needed. You can get such a pool using
`Executors.newCachedThreadPool()` as in the example of the previous section. But
take care of shutting it down when the server is stopped!

### Solution
Well, the solution is quite straighforward. We only need to create a thread pool
and execution context, and use it whenever we need to read input from some
connected client. So the beginning of the `loop` function would look like:

```scala
for{
  _     <- IO.shift(clientsExecutionContext)
  lineE <- IO(reader.readLine()).attempt
  _     <- IO.shift(ExecutionContext.global)
  //
```

and... that is mostly it. Only pending change is to create the thread pool and
execution context in the `server` function, which will be in charge also of
shutting down the thread pool when the server finishes:

```scala
import cats.effect._
import cats.effect.concurrent.MVar
import cats.implicits._
import java.net.ServerSocket
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global

def serve(serverSocket: ServerSocket, stopFlag: MVar[IO, Unit])(implicit clientsExecutionContext: ExecutionContext): IO[Unit] = ???

def server(serverSocket: ServerSocket): IO[ExitCode] = {

  val clientsThreadPool = Executors.newCachedThreadPool()
  implicit val clientsExecutionContext = ExecutionContext.fromExecutor(clientsThreadPool)
  implicit val contextShift = IO.contextShift(global)

  for {
    stopFlag     <- MVar[IO].empty[Unit]
    serverFiber  <- serve(serverSocket, stopFlag).start
    _            <- stopFlag.read *> IO{println(s"Stopping server")}
    _            <- IO{clientsThreadPool.shutdown()}
    _            <- serverFiber.cancel
  } yield ExitCode.Success

}
```

Signatures of `serve` and of `echoProtocol` will have to be changed too to pass
the execution context as parameter. The resulting server code is [available in
github](https://github.com/lrodero/cats-effect-tutorial/blob/master/src/main/scala/catsEffectTutorial/EchoServerV4_ClientThreadPool).


## Let's not forget about `async`

The `async` functionality is another powerful capability of cats-effect we have
not mentioned yet. It allows to build `IO` instances that may be terminated by a
thread different than the one carrying the evaluation of that instance. Result
will be returned by using a callback.

Some of you may wonder if that could help us to solve the issue of having
blocking code in our fabolous echo server. Unfortunately, `async` cannot
magically 'unblock' such code. Try this simple code snippet:

```scala
import cats.effect.IO
import cats.implicits._
import scala.util.Either

val delayedIO = for {
  _ <- IO(println("Starting"))
  _ <- IO.async[Unit]{ (cb: Either[Throwable,Unit] => Unit) =>
      Thread.sleep(2000)
      cb(Right(()))
    }
  _ <- IO(println("Done")) // 2 seconds to get here, no matter what
} yield()

delayedIO.unsafeRunSync()
```

You will notice that the code still blocks. So how is `async` useful? Well,
because it allows a different thread to finish the task, we can modify the
blocking read call inside the `loop` function of our server with something like:

```scala
for {
  lineE <- IO.async{ (cb: Either[Throwable, Either[Throwable, String]] => Unit) => 
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
that execution context, setting free the thread that was evaluating the `IO`
for-comprehension. If the thread pool used by the execution context can create
new threads if no free ones are available, then we will be able to attend as
many clients as needed. This is similar to the solution we used previously
shifting the task to the execution context! And in fact `shift` makes something
close to what we have just coded: it introduces asynchronous boundaries to
reorganize how threads are used. The final result will be identical to our
previous server version. To attend client connections, if no thread is available
in the pool, new threads will be created in the pool. A full version of our echo
server using this approach is [available in
github](https://github.com/lrodero/cats-effect-tutorial/blob/master/src/main/scala/catsEffectTutorial/EchoServerV5_Async.scala).

### When is `async` useful then?

The `async` construct is useful specially when the task to run by the `IO` can
be terminated by any thread. For example, calls to remote services are often
modelled with `Future`s so they do not block the calling thread. When defining
our `IO`, should we block on the `Future` waiting for the result? No! We can
wrap the call in an `async` call like:

```scala
import cats.effect.IO
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util._

trait Service { def getSomething(): Future[String] }
def service: Service = ???

IO.async[String]{ (cb: Either[Throwable, String] => Unit) => 
  service.getSomething().onComplete {
    case Success(s) => cb(Right(s))
    case Failure(e) => cb(Left(e))
  }
}
```

So, our aim is to create an echo server that does not require a thread per
connected socket to wait on the blocking `read()` method. If we use a network
library that avoids or at limits blocking operations, we could then combine that
with `async` to create such non-blocking server. And Java NIO can be helpful
here. While Java NIO does have some blocking method (`Selector`'s `select()`),
it allows to build servers that do not require a thread per connected client:
`select()` will return those 'channels' (such as `SochetChannel`) that have data
available to read from, then processing of the incoming data can be split among
threads of a size-bounded pool. This way, a thread-per-client approach is not
needed. Java NIO2 or netty could also be applicable to this scenario. We leave
as a final exercise to implement again our echo server but this time using an
async lib.

## Conclusion

With all this we have covered a good deal of what cats-effect has to offer (but
not all!). Now you are ready to use to create code that operate side effects in
a purely functional manner. Enjoy the ride!
