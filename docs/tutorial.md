---
id: tutorial
title: Tutorial
---

## Introduction

This tutorial tries to help newcomers to cats-effect to get familiar with its
main concepts by means of code examples, in a learn-by-doing fashion. Two small
programs will be coded, each one in its own section. [The first
one](#copyingfiles) copies the contents from one file to another, safely
handling _resources_ and _cancelation_ in the process. That should help us to
flex our muscles. [The second one](#producerconsumer) implements a solution to
the producer-consumer problem to introduce cats-effect _fibers_.

This tutorial assumes some familiarity with functional programming. It is
also a good idea to read the cats-effect documentation prior to starting this
tutorial, at least the [Getting Started page](getting-started.md).

Please read this tutorial as training material, not as a best-practices
document. As you gain more experience with cats-effect, you will probably find
your own solutions to deal with the problems presented here. Also, bear in mind
that using cats-effect for copying files or implementing basic concurrency
patterns (such as the producer-consumer problem) is suitable for a 'getting
things done' approach, but for more complex systems/settings/requirements you
might want to take a look at [fs2](http://fs2.io) or [Monix](https://monix.io)
to find powerful network and file abstractions that integrate with cats-effect.
But that is beyond the purpose of this tutorial, which focuses solely on
cats-effect.

That said, let's go!

## Setting things up

This [Github
repo](https://github.com/lrodero/cats-effect-tutorial/tree/series/3.x) includes
all the software that will be developed during this tutorial (branch
`series/3.x`). It uses `sbt` as the build tool. To ease coding, compiling and
running the code snippets in this tutorial, it is recommended to use the same
`build.sbt`, or at least one with the same dependencies and compilation options:

```scala
name := "cats-effect-tutorial"

version := "3.5.7"

scalaVersion := "2.13.13"

libraryDependencies += "org.typelevel" %% "cats-effect" % "3.5.7" withSources() withJavadoc()

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
)
```
Also make sure that you use a recent version of `sbt`, _e.g._ `1.9.8`. You can set
the `sbt` version in `project/build.properties` file:

```scala
sbt.version=1.9.8
```

Almost all code snippets in this tutorial can be pasted and compiled right in
the scala console of the project defined above (or any project with similar
settings).

## <a name="copyingfiles"></a>Copying files - basic concepts, resource handling and cancelation

Our goal is to create a program that copies files. First we will work on a
function that carries out such a task, and then we will create a program that can be
invoked from the shell and uses that function.

First of all we must code the function that copies the content from a file to
another file. The function takes the source and destination files as parameters.
But this is functional programming! So invoking the function shall not copy
anything, instead it will return an `IO` instance that encapsulates all the side
effects involved (opening/closing files, reading/writing content), that way
_purity_ is kept.  Only when that `IO` instance is evaluated all those
side-effectful actions will be run. In our implementation the `IO` instance will
return the amount of bytes copied upon execution, but this is just a design
decision. Of course errors can occur, but when working with any `IO` those
should be embedded in the `IO` instance. That is, no exception is raised outside
the `IO` and so no `try` (or the like) needs to be used when using the function,
instead the `IO` evaluation will fail and the `IO` instance will carry the error
raised.

Now, the signature of our function looks like this:

```scala mdoc:compile-only
import cats.effect.IO
import java.io.File

def copy(origin: File, destination: File): IO[Long] = ???
```

Nothing scary, eh? As we said before, the function just returns an `IO`
instance. When run, all side-effects will be actually executed and the `IO`
instance will return the bytes copied in a `Long` (note that `IO` is
parameterized by the return type). Now, let's start implementing our function.
First, we need to open two streams that will read and write file contents.

### Acquiring and releasing `Resource`s
We consider opening a stream to be a side-effectful action, so we have to
encapsulate those actions in their own `IO` instances. We can just embed the
actions by calling `IO(action)`, but when dealing with input/output actions it
is advised to use instead `IO.blocking(action)`. This way we help cats-effect to
better plan how to assign threads to actions. We will return to this topic when
we introduce _fibers_ later on in this tutorial.

Also, we will make use of cats-effect `Resource`. It allows to orderly create,
use and then release resources. See this code:

```scala mdoc:compile-only
import cats.effect.{IO, Resource}
import java.io._

def inputStream(f: File): Resource[IO, FileInputStream] =
  Resource.make {
    IO.blocking(new FileInputStream(f))                         // build
  } { inStream =>
    IO.blocking(inStream.close()).handleErrorWith(_ => IO.unit) // release
  }

def outputStream(f: File): Resource[IO, FileOutputStream] =
  Resource.make {
    IO.blocking(new FileOutputStream(f))                         // build
  } { outStream =>
    IO.blocking(outStream.close()).handleErrorWith(_ => IO.unit) // release
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
for-comprehensions. Note also that when releasing resources we must also take
care of any possible error during the release itself, for example with the
`.handleErrorWith` call as we do in the code above.  In this case we just
ignore the error, but normally it should be at least logged. Often you will see
that `.attempt.void` is used to get the same 'swallow and ignore errors'
behavior.

Optionally we could have used `Resource.fromAutoCloseable` to define our
resources, that method creates `Resource` instances over objects that implement the
`java.lang.AutoCloseable` interface without having to define how the resource is
released. So our `inputStream` function would look like this:

```scala mdoc:compile-only
import cats.effect.{IO, Resource}
import java.io.{File, FileInputStream}

def inputStream(f: File): Resource[IO, FileInputStream] =
  Resource.fromAutoCloseable(IO.blocking(new FileInputStream(f)))
```

That code is way simpler, but with that code we would not have control over what
would happen if the closing operation throws an exception. Also it could be that
we want to be aware when closing operations are being run, for example using
logs. In contrast, using `Resource.make` allows to easily control the actions
of the release phase.

Let's go back to our `copy` function, which now looks like this:

```scala mdoc:compile-only
import cats.effect.{IO, Resource}
import java.io._

// as defined before
def inputOutputStreams(in: File, out: File): Resource[IO, (InputStream, OutputStream)] = ???

// transfer will do the real work
def transfer(origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): IO[Long] = ???

def copy(origin: File, destination: File): IO[Long] =
  inputOutputStreams(origin, destination).use { case (in, out) =>
    transfer(in, out, new Array[Byte](1024 * 10), 0)
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
Now, if you are familiar with cats-effect's `Bracket` you may be wondering why
we are not using it as it looks so similar to `Resource` (and there is a good
reason for that: `Resource` is based on `bracket`). Ok, before moving forward it
is worth taking a look at `bracket`.

There are three stages when using `bracket`: _resource acquisition_, _usage_,
and _release_. Each stage is defined by an `IO` instance.  A fundamental
property is that the _release_ stage will always be run regardless whether the
_usage_ stage finished correctly or an exception was thrown during its
execution. In our case, in the _acquisition_ stage we would create the streams,
then in the _usage_ stage we will copy the contents, and finally in the release
stage we will close the streams.  Thus we could define our `copy` function as
follows:

```scala mdoc:compile-only
import cats.effect.IO
import cats.syntax.all._
import java.io._

// function inputOutputStreams not needed

// transfer will do the real work
def transfer(origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): IO[Long] = ???

def copy(origin: File, destination: File): IO[Long] = {
  val inIO: IO[InputStream]  = IO.blocking(new FileInputStream(origin))
  val outIO:IO[OutputStream] = IO.blocking(new FileOutputStream(destination))

  (inIO, outIO)              // Stage 1: Getting resources
    .tupled                  // From (IO[InputStream], IO[OutputStream]) to IO[(InputStream, OutputStream)]
    .bracket{
      case (in, out) =>
        transfer(in, out, new Array[Byte](1024 * 10), 0L) // Stage 2: Using resources (for copying data, in this case)
    } {
      case (in, out) =>      // Stage 3: Freeing resources
        (IO(in.close()), IO(out.close()))
        .tupled              // From (IO[Unit], IO[Unit]) to IO[(Unit, Unit)]
        .void.handleErrorWith(_ => IO.unit)
    }
}
```

The new `copy` definition is more complex, even though the code as a whole is way
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
of the bytes transferred. 

```scala mdoc:compile-only
import cats.effect.IO
import java.io._

def transfer(origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): IO[Long] =
  for {
    amount <- IO.blocking(origin.read(buffer, 0, buffer.length))
    count <- if (amount > -1) IO.blocking(destination.write(buffer, 0, amount)) >> transfer(origin, destination, buffer, acc + amount)
             else IO.pure(acc) // End of read stream reached (by java.io.InputStream contract), nothing to write
  } yield count // Returns the actual amount of bytes transferred
```

Take a look at `transfer`, observe that both input and output actions are
created by invoking `IO.blocking` which return the actions encapsulated in
(suspended in) `IO`. We can also just embed the actions by calling `IO(action)`,
but when dealing with input/output actions it is advised that you instead use
`IO.blocking(action)`. This way we help cats-effect to better plan how to assign
threads to actions. We will return to this topic when we introduce _fibers_
later on in this tutorial.

`IO` being a monad, we can sequence our new `IO` instances using a
for-comprehension to create another `IO`. The for-comprehension loops as long as
the call to `read()` does not return a negative value that would signal that the
end of the stream has been reached. `>>` is a Cats operator to sequence two
operations where the output of the first is not needed by the second (_i.e._ it
is equivalent to `first.flatMap(_ => second)`). In the code above that means
that after each write operation we recursively call `transfer` again, but as
`IO` is stack safe we are not concerned about stack overflow issues. At each
iteration we increase the counter `acc` with the amount of bytes read at that
iteration.

We are making progress, and already have a version of `copy` that can be used.
If any exception is raised when `transfer` is running, then the streams will be
automatically closed by `Resource`. But there is something else we have to take
into account: `IO` instances execution can be **_canceled!_** And cancelation
should not be ignored, as it is a key feature of cats-effect. We will discuss
cancelation in the next section.

### Dealing with cancelation
Cancelation is a powerful but non-trivial cats-effect feature. In cats-effect,
some `IO` instances can be canceled (_e.g._ by other `IO` instances running
concurrently) meaning that their evaluation will be aborted. If the programmer is
careful, an alternative `IO` task will be run under cancelation, for example to
deal with potential cleaning up activities.

Thankfully, `Resource` makes dealing with cancelation an easy task. If the `IO`
inside a `Resource.use` is canceled, the release section of that resource is
run. In our example this means the input and output streams will be properly
closed. Also, cats-effect does not cancel code inside `IO.blocking` instances.
In the case of our `transfer` function this means the execution would be
interrupted only between two calls to `IO.blocking`. If we want the execution of
an IO instance to be interrupted when canceled, without waiting for it to
finish, we must instantiate it using `IO.interruptible`.

### `IOApp` for our final program

We will create a program that copies files, this program only takes two
parameters: the name of the origin and destination files. For coding this
program we will use `IOApp` as it allows to maintain purity in our definitions
up to the program main function.

`IOApp` is a kind of 'functional' equivalent to Scala's `App`, where instead of
coding an effectful `main` method we code a pure `run` function. When executing
the class a `main` method defined in `IOApp` will call the `run` function we
have coded. Any interruption (like pressing `Ctrl-c`) will be treated as a
cancelation of the running `IO`.

When coding `IOApp`, instead of a `main` function we have a `run` function,
which creates the `IO` instance that forms the program. In our case, our `run`
method can look like this:

```scala mdoc:compile-only
import cats.effect._
import java.io.File

object Main extends IOApp {

  // copy as defined before
  def copy(origin: File, destination: File): IO[Long] = ???

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- IO.raiseWhen(args.length < 2)(new IllegalArgumentException("Need origin and destination files"))
      orig = new File(args(0))
      dest = new File(args(1))
      count <- copy(orig, dest)
      _     <- IO.println(s"$count bytes copied from ${orig.getPath} to ${dest.getPath}")
    } yield ExitCode.Success
}
```

Heed how `run` verifies the `args` list passed. If there are fewer than two
arguments, an error is raised. As `IO` implements `MonadError` we can at any
moment call to `IO.raiseWhen` or `IO.raiseError` to interrupt a sequence of `IO`
operations. The log message is printed by means of the handy `IO.println`
method.

#### Copy program code
You can check the [final version of our copy program
here](https://github.com/lrodero/cats-effect-tutorial/blob/series/3.x/src/main/scala/catseffecttutorial/copyfile/CopyFile.scala).

The program can be run from `sbt` just by issuing this call:

```scala
> runMain catseffecttutorial.copyfile.CopyFile origin.txt destination.txt
```

It can be argued that using `IO{java.nio.file.Files.copy(...)}` would get an
`IO` with the same characteristics of purity as our function. But there is a
difference: our `IO` is safely cancelable! So the user can stop the running code
at any time for example by pressing `Ctrl-c`, our code will deal with safe
resource release (streams closing) even under such circumstances. The same will
apply if the `copy` function is run from other modules that require its
functionality. If the `IO` returned by this function is canceled while being
run, still resources will be properly released.

### Polymorphic cats-effect code
There is an important characteristic of `IO` that we should be aware of. `IO` is
able to suspend side-effects asynchronously thanks to the existence of an
instance of `Async[IO]`. Because `Async` extends `Sync`, `IO` can also suspend
side-effects synchronously. On top of that `Async` extends typeclasses such as
`MonadCancel`, `Concurrent` or `Temporal`, which bring the possibility to cancel
an `IO` instance, to run several `IO` instances concurrently, to timeout an
execution, to force the execution to wait (sleep), etc.

So well, `Sync` and `Async` can suspend side effects. We have used `IO` so far
mostly for that purpose. Now, going back to the code we created to copy files,
could have we coded its functions in terms of some `F[_]: Sync` and `F[_]:
Async` instead of `IO`?  Truth is we could, see for example how we would define
a polymorphic version of our `transfer` function with this approach, just by
replacing any use of `IO` by calls to the `delay` and `pure` methods of the
`Sync[F]` instance:

```scala mdoc:compile-only
import cats.effect.Sync
import cats.syntax.all._
import java.io._

def transfer[F[_]: Sync](origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): F[Long] =
  for {
    amount <- Sync[F].blocking(origin.read(buffer, 0, buffer.length))
    count  <- if(amount > -1) Sync[F].blocking(destination.write(buffer, 0, amount)) >> transfer(origin, destination, buffer, acc + amount)
              else Sync[F].pure(acc) // End of read stream reached (by java.io.InputStream contract), nothing to write
  } yield count // Returns the actual amount of bytes transferred
```

We leave as an exercise to code the polymorphic versions of `inputStream`,
`outputStream`, `inputOutputStreams` and `copy` functions.

```scala mdoc:compile-only
import cats.effect._
import java.io._

def transfer[F[_]: Sync](origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): F[Long] = ???
def inputStream[F[_]: Sync](f: File): Resource[F, FileInputStream] = ???
def outputStream[F[_]: Sync](f: File): Resource[F, FileOutputStream] = ???
def inputOutputStreams[F[_]: Sync](in: File, out: File): Resource[F, (InputStream, OutputStream)] = ???
def copy[F[_]: Sync](origin: File, destination: File): F[Long] = ???
```

Only in our `main` function we will set `IO` as the final `F` for our program.
To do so, of course, a `Sync[IO]` instance must be in scope, but that instance
is brought transparently by `IOApp` so we do not need to be concerned about it.

During the remainder of this tutorial we will use polymorphic code, only falling
to `IO` in the `run` method of our `IOApp`s. Polymorphic code is less
restrictive, as functions are not tied to `IO` but are applicable to any `F[_]`
as long as there is an instance of the type class required (`Sync[F[_]]` ,
`Async[F[_]]`...) in scope. The type class to use will depend on the
requirements of our code.

#### Copy program code, polymorphic version
The polymorphic version of our copy program in full is available
[here](https://github.com/lrodero/cats-effect-tutorial/blob/series/3.x/src/main/scala/catseffecttutorial/copyfile/CopyFilePolymorphic.scala).

### Exercises: improving our small `IO` program

To finalize we propose you some exercises that will help you to keep improving
your IO-kungfu:

1. Modify the `IOApp` so it shows an error and abort the execution if the origin
   and destination files are the same, the origin file cannot be open for
   reading, or the destination file cannot be opened for writing. Also, if the
   destination file already exists, the program should ask for confirmation
   before overwriting that file.
2. Test safe cancelation, checking that the streams are indeed being properly
   closed. You can do that just by interrupting the program execution pressing
   `Ctrl-c`. To make sure you have the time to interrupt the program, introduce
   a delay of a few seconds in the `transfer` function (see `IO.sleep`). And to
   ensure that the release functionality in the `Resource`s is run you can add
   some log message there (see `IO.println`).
3. Create a new program able to copy folders. If the origin folder has
   subfolders, then their contents must be recursively copied too. Of course the
   copying must be safely cancelable at any moment.

## <a name="producerconsumer"></a>Producer-consumer problem - concurrency and fibers
The _producer-consumer_ pattern is often found in concurrent setups. Here one or
more producers insert data on a shared data structure like a queue while one or
more consumers extract data from it. Readers and writers run concurrently. If
the queue is empty then readers will block until data is available, if the queue
is full then writers will wait for some 'bucket' to be free. Only one writer at
a time can add data to the queue to prevent data corruption. Also only one
reader can extract data from the queue so no two readers get the same data item.

Variations of this problem exist depending on whether there are more than one
consumer/producer, or whether the data structure sitting between them is
size-bounded or not. The solutions discussed here are suited for multi-consumer
and multi-reader settings. Initially we will assume an unbounded data structure,
and later present a solution for a bounded one.

But before we work on the solution for this problem we must introduce _fibers_,
which are the basic building block of cats-effect concurrency.

### Intro to fibers
A fiber carries an `F` action to execute (typically an `IO` instance). Fibers
are like 'light' threads, meaning they can be used in a similar way than threads
to create concurrent code. However, they are _not_ threads. Spawning new fibers
does not guarantee that the action described in the `F` associated to it will be
run if there is a shortage of threads. Internally cats-effect uses thread pools
to run fibers when running on the JVM. So if there is no thread available in the
pool then the fiber execution will 'wait' until some thread is free again. On
the other hand when the execution of some fiber is blocked _e.g._ because it
must wait for a semaphore to be released, the thread running the fiber is
recycled by cats-effect so it is available for other fibers. When the fiber
execution can be resumed cats-effect will look for some free thread to continue
the execution. The term "_fiber blocking_" is used sometimes to denote
that blocking the fiber does not involve halting any thread. Cats-effect also
recycles threads of finished and canceled fibers. But keep in mind that, in
contrast, if the fiber is truly blocked by some external action like waiting for
some input from a TCP socket, then cats-effect has no way to recover back that
thread until the action finishes. Such calls should be wrapped by `IO.blocking`
to signal that the wrapped code will block the thread.  Cats-effect uses that
info as a hint to optimize `IO` scheduling.

Another difference with threads is that fibers are very cheap entities. We can
spawn millions of them at ease without impacting the performance.

A worthy note is that you do not have to explicitly shut down fibers. If you
spawn a fiber and it finishes actively running its `IO` it will get cleaned up
by the garbage collector unless there is some other active memory reference to
it. So basically you can treat a fiber as any other regular object, except that
when the fiber is _running_ (present tense), the cats-effect runtime itself
keeps the fiber alive.

This has some interesting implications as well. Like if you create an `IO.async`
node and register the callback with something, and you're in a fiber which has
no strong object references anywhere else (i.e. you did some sort of
fire-and-forget thing), then the callback itself is the only strong reference to
the fiber. Meaning if the registration fails or the system you registered with
throws it away, the fiber will just gracefully disappear.

And a final hint: as with threads, often you will need to coordinate the work of
concurrent fibers. Writing concurrent code is a difficult exercise, but
cats-effect implements some concurrency primitives such as
[Deferred](std/deferred.md), [Ref](std/ref.md), [Semaphore](std/semaphore.md)...
that will help you in that task. Way more detailed info about concurrency in
cats-effect can be found in [this other tutorial 'Concurrency in Scala with
Cats-Effect'](https://github.com/slouc/concurrency-in-scala-with-ce).

Ok, now we have briefly discussed fibers we can start working on our
producer-consumer problem.

### First (and inefficient) implementation
We need an intermediate structure where producer(s) can insert data to and
consumer(s) extracts data from. Let's assume a simple queue. Initially there
will be only one producer and one consumer. Producer will generate a sequence of
integers (`1`, `2`, `3`...), consumer will just read that sequence.  Our shared
queue will be an instance of an immutable `Queue[Int]`.

Accesses to the queue can (and will!) be concurrent, thus we need some way to
protect the queue so only one fiber at a time is handling it. A good way to
ensure an ordered access to some shared data is [Ref](std/ref.md). A `Ref`
instance wraps some given data and implements methods to manipulate that data in
a safe manner.

The `Ref` wrapping our queue will be `Ref[F, Queue[Int]]` (for some `F[_]`).

Now, our `producer` method will be:
```scala mdoc:compile-only
import cats.effect._
import cats.effect.std.Console
import cats.syntax.all._
import scala.collection.immutable.Queue

def producer[F[_]: Sync: Console](queueR: Ref[F, Queue[Int]], counter: Int): F[Unit] =
  for {
    _ <- Sync[F].whenA(counter % 10000 == 0)(Console[F].println(s"Produced $counter items"))
    _ <- queueR.getAndUpdate(_.enqueue(counter + 1))
    _ <- producer(queueR, counter + 1)
  } yield ()
```

First line just prints some log message every `10000` items, so we know if our
producer is still 'alive'. We can as well do `if(cond) then Console... else
Sync[F].unit` but this approach is more idiomatic. To print logs the code uses
type class `Console[_]`, which brings the capacity to print and read strings
(the `IO.println` call we used before just invokes `Console[IO].println` under
the hood).

Then our code calls `queueR.getAndUpdate` to add data into the queue. Note
that `.getAndUpdate` provides the current queue, then we use `.enqueue` to
insert the next value `counter+1`. This call returns a new queue with the value
added that is stored by the ref instance. If some other fiber is accessing to
`queueR` then the fiber (but no thread) is blocked.

The `consumer` method is a bit different. It will try to read data from the
queue but it must be aware that the queue can be empty:

```scala mdoc:compile-only
import cats.effect._
import cats.effect.std.Console
import cats.syntax.all._
import scala.collection.immutable.Queue

def consumer[F[_]: Sync: Console](queueR: Ref[F, Queue[Int]]): F[Unit] =
  for {
    iO <- queueR.modify{ queue =>
      queue.dequeueOption.fold((queue, Option.empty[Int])){case (i,queue) => (queue, Option(i))}
    }
    _ <- Sync[F].whenA(iO.exists(_ % 10000 == 0))(Console[F].println(s"Consumed ${iO.get} items"))
    _ <- consumer(queueR)
  } yield ()
```

The call to `queueR.modify` allows to modify the wrapped data (our queue) and
return a value that is computed from that data. In our case, it returns an
`Option[Int]` that will be `None` if queue was empty. Next line is used to log
a message in console every `10000` read items. Finally `consumer` is called
recursively to start again.

We can now create a program that instantiates our `queueR` and runs both
`producer` and `consumer` in parallel:

```scala mdoc:compile-only
import cats.effect._
import cats.effect.std.Console
import cats.syntax.all._
import scala.collection.immutable.Queue

object InefficientProducerConsumer extends IOApp {

  def producer[F[_]: Sync](queueR: Ref[F, Queue[Int]], counter: Int): F[Unit] = ??? // As defined before
  def consumer[F[_]: Sync](queueR: Ref[F, Queue[Int]]): F[Unit] = ??? // As defined before

  override def run(args: List[String]): IO[ExitCode] =
    for {
      queueR <- Ref.of[IO, Queue[Int]](Queue.empty[Int])
      res <- (consumer(queueR), producer(queueR, 0))
        .parMapN((_, _) => ExitCode.Success) // Run producer and consumer in parallel until done (likely by user cancelling with CTRL-C)
        .handleErrorWith { t =>
          Console[IO].errorln(s"Error caught: ${t.getMessage}").as(ExitCode.Error)
        }
    } yield res

}
```

Our `run` function instantiates the shared queue wrapped in a `Ref` and boots
the producer and consumer in parallel. To do to it uses `parMapN`, that creates
and runs the fibers that will run the `IO`s passed as parameter. Then it takes
the output of each fiber and applies a given function to them. In our case
both producer and consumer shall run forever until the user presses CTRL-C which
will trigger a cancelation.

Alternatively we could have used `start` method to explicitly create new
`Fiber` instances that will run the producer and consumer, then use `join` to
wait for them to finish, something like:

```scala mdoc:compile-only
import cats.effect._
import scala.collection.immutable.Queue

object InefficientProducerConsumer extends IOApp {

  def producer[F[_]: Sync](queueR: Ref[F, Queue[Int]], counter: Int): F[Unit] = ??? // As defined before
  def consumer[F[_]: Sync](queueR: Ref[F, Queue[Int]]): F[Unit] = ??? // As defined before

  def run(args: List[String]): IO[ExitCode] =
    for {
      queueR <- Ref.of[IO, Queue[Int]](Queue.empty[Int])
      producerFiber <- producer(queueR, 0).start
      consumerFiber <- consumer(queueR).start
      _ <- producerFiber.join
      _ <- consumerFiber.join
    } yield ExitCode.Error
}
```

However in most situations it is not advisable to handle fibers manually as
they are not trivial to work with. For example, if there is an error in a fiber
the `join` call to that fiber will _not_ raise it, it will return normally and
you must explicitly check the `Outcome` instance returned by the `.join` call
to see if it errored. Also, the other fibers will keep running unaware of what
happened.

Cats Effect provides additional `joinWith` or `joinWithNever` methods to make
sure at least that the error is raised with the usual `MonadError` semantics
(_i.e._, short-circuiting).  Now that we are raising the error, we also need to
cancel the other running fibers. We can easily get ourselves trapped in a
tangled mess of fibers to keep an eye on.  On top of that the error raised by a
fiber is not promoted until the call to `joinWith` or `.joinWithNever` is
reached. So in our example above if `consumerFiber` raises an error then we
have no way to observe that until the producer fiber has finished. Alarmingly,
note that in our example the producer _never_ finishes and thus the error would
_never_ be observed!  And even if the producer fiber did finish, it would have
been consuming resources for nothing.

In contrast `parMapN` does promote any error it finds to the caller _and_ takes
care of canceling the other running fibers. As a result `parMapN` is simpler to
use, more concise, and easier to reason about. _Because of that, unless you
have some specific and unusual requirements you should prefer to use higher
level commands such as `parMapN` or `parSequence` to work with fibers_.

Ok, we stick to our implementation based on `.parMapN`. Are we done? Does it
Work? Well, it works... but it is far from ideal.

#### Issue 1: the producer outpaces the consumer
Now, if you run the program you will notice that almost no consumer logs are
shown, if any. This is a signal that the producer is running way faster than
the consumer. And why is that? Well, this is because how `Ref.modify` works. It
gets the current value, then it computes the update, and finally it tries to set
the new value if the current one has not been changed (by some other fiber),
otherwise it starts from the beginning. Unfortunately the producer is way faster
running its `queueR.getAndUpdate` call than the consumer is running its
`queueR.modify` call. So the consumer gets 'stuck' trying once and again to
update the `queueR` content.

Can we alleviate this? Sure! There are a few options you can implement:
1. Making the producer artificially slower by introducing a call to `Async[F].sleep`
   (_e.g._ for 1 microsecond). Truth is, in real world scenarios a producer will
   not be as fast as in our example so this tweak is not that 'strange'. Note that
   to be able to use `sleep` now `F` requires an implicit `Async[F]` instance. The
   new producer will look like this:
   ```scala mdoc:compile-only
   import cats.effect._
   import cats.effect.std.Console
   import cats.syntax.all._
   import scala.collection.immutable.Queue
   import scala.concurrent.duration.DurationInt
   
   def producer[F[_]: Async: Console](queueR: Ref[F, Queue[Int]], counter: Int): F[Unit] =
     for {
       _ <- Async[F].whenA(counter % 10000 == 0)(Console[F].println(s"Produced $counter items"))
       _ <- Async[F].sleep(1.microsecond) // To prevent overwhelming consumers
       _ <- queueR.getAndUpdate(_.enqueue(counter + 1))
       _ <- producer(queueR, counter + 1)
     } yield ()
   ```
2. Replace `Ref` with `AtomicCell` to keep the `Queue` instance. `AtomicCell`,
   like `Ref`, is a concurrent data structure to keep a reference to some data.
   But unlike `Ref` it ensures that only one fiber can operate on that reference
   at any given time. Thus the consumer won't have to try once and again to modify
   its content. On the other hand `AtomicCell` is slower than `Ref`. This is
   because `Ref` is nonblocking while `AtomicCell` will block calling fibers to
   ensure only one operates on its content.
3. Make the queue bound by size so producers are forced to wait for consumers to
   extract data when the queue is full. We will do this later on in Section
   [Producer consumer with bounded
queue](#producer-consumer-with-bounded-queue).

By the way, you may be tempted to speed up the `queueR.modify` call in the
consumer by using a mutable `Queue` instance. Do not! `Ref` _must_ be used only
with immutable data.

#### Issue 2: consumer runs even if there are no elements in the queue
The consumer will be continually running regardless if there are elements in the
queue, which is far from ideal. If we have several consumers competing for the
data the problem gets even worse. We will address this problem in the next
section by using [Deferred](std/deferred.md).

The full implementation of the naive producer consumer we have just created in
this section is available 
[here](https://github.com/lrodero/cats-effect-tutorial/blob/series/3.x/src/main/scala/catseffecttutorial/producerconsumer/InefficientProducerConsumer.scala).

### A more solid implementation of the producer/consumer problem
In our producer/consumer code we already protect access to the queue (our shared
resource) using a `Ref`. Now, instead of using `Option` to represent elements
retrieved from a possibly empty queue, we should instead block the caller fiber
somehow if queue is empty until some element can be returned. This way we
prevent having consumer fibers running even when there is no element to
consume. This will be done by creating and keeping instances of `Deferred`. A
`Deferred[F, A]` instance can hold one single element of some type `A`.
`Deferred` instances are created empty, and can be filled only once. If some
fiber tries to read the element from an empty `Deferred` then it will wait
until some other fiber fills (completes) it. But recall that this waiting does
not involve blocking any physical thread, that's the beauty of fibers! 

Also, we will step up our code so we can handle several producers and consumers
in parallel.

Ok, so, alongside the queue of produced but not yet consumed elements, we have
to keep track of the `Deferred` instances (created because consumers found an
emnpty queue) that are waiting for elements to be available. These instances
will be kept in a new queue `takers`. We will keep both queues in a new type
`State`:

```scala mdoc:compile-only
import cats.effect.Deferred
import scala.collection.immutable.Queue
case class State[F[_], A](queue: Queue[A], takers: Queue[Deferred[F,A]])
```

Both producer and consumer will access the same shared state instance, which
will be carried and safely modified by an instance of `Ref`. Consumer shall
work as follows:
1. If `queue` is not empty, it will extract and return its head. The new state
   will keep the tail of the queue, no change on `takers` will be needed.
2. If `queue` is empty it will use a new `Deferred` instance as a new `taker`,
   add it to the `takers` queue, and 'block' the caller by invoking `taker.get`

Assuming that in our setting we produce and consume `Int`s (just as before),
then new consumer code will then be:

```scala mdoc:compile-only
import cats.effect.{Deferred, Ref, Async}
import cats.effect.std.Console
import cats.syntax.all._
import scala.collection.immutable.Queue

case class State[F[_], A](queue: Queue[A], takers: Queue[Deferred[F,A]])

def consumer[F[_]: Async: Console](id: Int, stateR: Ref[F, State[F, Int]]): F[Unit] = {

  val take: F[Int] =
    Deferred[F, Int].flatMap { taker =>
      stateR.modify {
        case State(queue, takers) if queue.nonEmpty =>
          val (i, rest) = queue.dequeue
          State(rest, takers) -> Async[F].pure(i) // Got element in queue, we can just return it
        case State(queue, takers) =>
          State(queue, takers.enqueue(taker)) -> taker.get // No element in queue, must block caller until some is available
      }.flatten
    }

  for {
    i <- take
    _ <- Async[F].whenA(i % 10000 == 0)(Console[F].println(s"Consumer $id has reached $i items"))
    _ <- consumer(id, stateR)
  } yield ()
}
```

The `id` parameter is only used to identify the consumer in console logs (recall
we will have now several producers and consumers running in parallel). The
`take` instance implements the checking and updating of the state in `stateR`.
Note how it will block on `taker.get` when the queue is empty.

The producer, for its part, will:
1. If there are waiting `takers`, it will take the first one in the takers queue
   and offer it the newly produced element (`taker.complete`).
2. If no `takers` are present, it will just enqueue the produced element.

Thus the producer will look like:

```scala mdoc:compile-only
import cats.effect.{Async, Deferred, Ref}
import cats.effect.std.Console
import cats.syntax.all._
import scala.collection.immutable.Queue
import scala.concurrent.duration.DurationInt

case class State[F[_], A](queue: Queue[A], takers: Queue[Deferred[F,A]])

def producer[F[_]: Async: Console](id: Int, counterR: Ref[F, Int], stateR: Ref[F, State[F,Int]]): F[Unit] = {

  def offer(i: Int): F[Unit] =
    stateR.modify {
      case State(queue, takers) if takers.nonEmpty =>
        val (taker, rest) = takers.dequeue
        State(queue, rest) -> taker.complete(i).void
      case State(queue, takers) =>
        State(queue.enqueue(i), takers) -> Async[F].unit
    }.flatten

  for {
    i <- counterR.getAndUpdate(_ + 1)
    _ <- offer(i)
    _ <- Async[F].whenA(i % 100000 == 0)(Console[F].println(s"Producer $id has reached $i items"))
    _ <- Async[F].sleep(1.microsecond) // To prevent overwhelming consumers
    _ <- producer(id, counterR, stateR)
  } yield ()
}
```

As in the previous section we introduce an artificial delay in order not to
overwhelm consumers. 

Finally we modify our main program so it instantiates the counter and state
`Ref`s. Also it will create several consumers and producers, 10 of each, and
will start all of them in parallel:

```scala mdoc:compile-only
import cats.effect._
import cats.effect.std.Console
import cats.instances.list._
import cats.syntax.all._
import scala.collection.immutable.Queue

object ProducerConsumer extends IOApp {

  case class State[F[_], A](queue: Queue[A], takers: Queue[Deferred[F,A]])

  object State {
    def empty[F[_], A]: State[F, A] = State(Queue.empty, Queue.empty)
  }

  def producer[F[_]: Sync](id: Int, counterR: Ref[F, Int], stateR: Ref[F, State[F,Int]]): F[Unit] = ??? // As defined before

  def consumer[F[_]: Async](id: Int, stateR: Ref[F, State[F, Int]]): F[Unit] = ??? // As defined before

  override def run(args: List[String]): IO[ExitCode] =
    for {
      stateR <- Ref.of[IO, State[IO,Int]](State.empty[IO, Int])
      counterR <- Ref.of[IO, Int](1)
      producers = List.range(1, 11).map(producer(_, counterR, stateR)) // 10 producers
      consumers = List.range(1, 11).map(consumer(_, stateR))           // 10 consumers
      res <- (producers ++ consumers)
        .parSequence.as(ExitCode.Success) // Run producers and consumers in parallel until done (likely by user cancelling with CTRL-C)
        .handleErrorWith { t =>
          Console[IO].errorln(s"Error caught: ${t.getMessage}").as(ExitCode.Error)
        }
    } yield res
}
```

The full implementation of this producer consumer with unbounded queue is
available
[here](https://github.com/lrodero/cats-effect-tutorial/blob/series/3.x/src/main/scala/catseffecttutorial/producerconsumer/ProducerConsumer.scala).

Producers and consumers are created as two list of `IO` instances. All of them
are started in their own fiber by the call to `parSequence`, which will wait for
all of them to finish and then return the value passed as parameter. As in the
previous example this program shall run forever until the user presses CTRL-C.

### Producer consumer with bounded queue
Having a bounded queue implies that producers, when the queue is full, will wait
(be 'fiber blocked') until there is some empty bucket available to be
filled.  So an implementation needs to keep track of these waiting producers. To
do so we will add a new queue `offerers` that will be added to the `State`
alongside `takers`.  For each waiting producer the `offerers` queue will keep a
`Deferred[F, Unit]` that will be used to block the producer until the element it
offers can be added to `queue` or directly passed to some consumer (`taker`).
Alongside the `Deferred` instance we need to keep as well the actual element
offered by the producer in the `offerers` queue. Thus `State` class now becomes:

```scala mdoc:compile-only
import cats.effect.Deferred
import scala.collection.immutable.Queue

case class State[F[_], A](queue: Queue[A], capacity: Int, takers: Queue[Deferred[F,A]], offerers: Queue[(A, Deferred[F,Unit])])
```

Of course both consumer and producer have to be modified to handle this new
queue `offerers`. A consumer can find four scenarios, depending on if `queue`
and `offerers` are each one empty or not. For each scenario a consumer shall:
1. If `queue` is not empty:
    1. If `offerers` is empty then it will extract and return `queue`'s head.
    2. If `offerers` is not empty (there is some producer waiting) then things
       are more complicated. The `queue` head will be returned to the consumer.
       Now we have a free bucket available in `queue`. So the first waiting
       offerer can use that bucket to add the element it offers. That element
       will be added to `queue`, and the `Deferred` instance will be completed
       so the producer is released (unblocked).
2. If `queue` is empty:
    1. If `offerers` is empty then there is nothing we can give to the caller,
       so a new `taker` is created and added to `takers` while caller is
       blocked with `taker.get`.
    2. If `offerers` is not empty then the first offerer in queue is extracted,
       its `Deferred` instance released while the offered element is returned to
       the caller.

So consumer code looks like this:

```scala mdoc:compile-only
import cats.effect._
import cats.effect.std.Console
import cats.syntax.all._
import scala.collection.immutable.Queue

case class State[F[_], A](queue: Queue[A], capacity: Int, takers: Queue[Deferred[F,A]], offerers: Queue[(A, Deferred[F,Unit])])

def consumer[F[_]: Async: Console](id: Int, stateR: Ref[F, State[F, Int]]): F[Unit] = {

  val take: F[Int] =
    Deferred[F, Int].flatMap { taker =>
      stateR.modify {
        case State(queue, capacity, takers, offerers) if queue.nonEmpty && offerers.isEmpty =>
          val (i, rest) = queue.dequeue
          State(rest, capacity, takers, offerers) -> Async[F].pure(i)
        case State(queue, capacity, takers, offerers) if queue.nonEmpty =>
          val (i, rest) = queue.dequeue
          val ((move, release), tail) = offerers.dequeue
          State(rest.enqueue(move), capacity, takers, tail) -> release.complete(()).as(i)
        case State(queue, capacity, takers, offerers) if offerers.nonEmpty =>
          val ((i, release), rest) = offerers.dequeue
          State(queue, capacity, takers, rest) -> release.complete(()).as(i)
        case State(queue, capacity, takers, offerers) =>
          State(queue, capacity, takers.enqueue(taker), offerers) -> taker.get
      }.flatten
    }

  for {
    i <- take
    _ <- Async[F].whenA(i % 100000 == 0)(Console[F].println(s"Consumer $id has reached $i items"))
    _ <- consumer(id, stateR)
  } yield ()
}
```

Producer functionality is a bit easier:
1. If there is any waiting `taker` then the produced element will be passed to
   it, releasing the blocked fiber.
2. If there is no waiting `taker` but `queue` is not full, then the offered
   element will be enqueued there.
3. If there is no waiting `taker` and `queue` is already full then a new
   `offerer` is created, blocking the producer fiber on the `.get` method of the
   `Deferred` instance.

Now producer code looks like this:

```scala mdoc:compile-only
import cats.effect._
import cats.effect.std.Console
import cats.syntax.all._
import scala.collection.immutable.Queue

case class State[F[_], A](queue: Queue[A], capacity: Int, takers: Queue[Deferred[F,A]], offerers: Queue[(A, Deferred[F,Unit])])

def producer[F[_]: Async: Console](id: Int, counterR: Ref[F, Int], stateR: Ref[F, State[F,Int]]): F[Unit] = {

  def offer(i: Int): F[Unit] =
    Deferred[F, Unit].flatMap[Unit]{ offerer =>
      stateR.modify {
        case State(queue, capacity, takers, offerers) if takers.nonEmpty =>
          val (taker, rest) = takers.dequeue
          State(queue, capacity, rest, offerers) -> taker.complete(i).void
        case State(queue, capacity, takers, offerers) if queue.size < capacity =>
          State(queue.enqueue(i), capacity, takers, offerers) -> Async[F].unit
        case State(queue, capacity, takers, offerers) =>
          State(queue, capacity, takers, offerers.enqueue(i -> offerer)) -> offerer.get
      }.flatten
    }

  for {
    i <- counterR.getAndUpdate(_ + 1)
    _ <- offer(i)
    _ <- Async[F].whenA(i % 100000 == 0)(Console[F].println(s"Producer $id has reached $i items"))
    _ <- producer(id, counterR, stateR)
  } yield ()
}
```

We have removed the `Async[F].sleep` call that we used to slow down producers
because we do not need it any more. Even if producers could run faster than
consumers they have to wait when the queue is full for consumers to extract data
from the queue. So at the end of the day they will run at the same pace.

As you see, producer and consumer are coded around the idea of keeping and
modifying state, just as with unbounded queues. Also we do not need to introduce
an artificial delay in producers, as soon as the queue gets full they will be
'blocked' thus giving a chance to consumers to read data.

As the final step we must adapt the main program to use these new consumers and
producers. Let's say we limit the queue size to `100`, then we have:

```scala mdoc:compile-only
import cats.effect._
import cats.effect.std.Console
import cats.syntax.all._

import scala.collection.immutable.Queue

object ProducerConsumerBounded extends IOApp {

  case class State[F[_], A](queue: Queue[A], capacity: Int, takers: Queue[Deferred[F,A]], offerers: Queue[(A, Deferred[F,Unit])])

  object State {
    def empty[F[_], A](capacity: Int): State[F, A] = State(Queue.empty, capacity, Queue.empty, Queue.empty)
  }

  def producer[F[_]: Async](id: Int, counterR: Ref[F, Int], stateR: Ref[F, State[F,Int]]): F[Unit] = ??? // As defined before

  def consumer[F[_]: Async](id: Int, stateR: Ref[F, State[F, Int]]): F[Unit] = ??? // As defined before

  override def run(args: List[String]): IO[ExitCode] =
    for {
      stateR <- Ref.of[IO, State[IO, Int]](State.empty[IO, Int](capacity = 100))
      counterR <- Ref.of[IO, Int](1)
      producers = List.range(1, 11).map(producer(_, counterR, stateR)) // 10 producers
      consumers = List.range(1, 11).map(consumer(_, stateR))           // 10 consumers
      res <- (producers ++ consumers)
        .parSequence.as(ExitCode.Success) // Run producers and consumers in parallel until done (likely by user cancelling with CTRL-C)
        .handleErrorWith { t =>
          Console[IO].errorln(s"Error caught: ${t.getMessage}").as(ExitCode.Error)
        }
    } yield res
}
```

The full implementation of this producer consumer with bounded queue is
available
[here](https://github.com/lrodero/cats-effect-tutorial/blob/series/3.x/src/main/scala/catseffecttutorial/producerconsumer/ProducerConsumerBounded.scala).

### Taking care of cancelation
We shall ask ourselves, is this implementation cancelation-safe? That is, what
happens if the fiber running a consumer or a producer gets canceled? Does state
become inconsistent? Let's check `producer` first. State is handled by its
internal `offer`, so we will focus on it. And, for the sake of clarity in our
analysis let's reformat the code using a for-comprehension:

```scala
import cats.effect.Deferred

def offer[F[_]](i: Int): F[Unit] =
  for {
    offerer <- Deferred[F, Int]
    op      <- stateR.modify {???} // `op` is an F[] to be run
    _       <- op
  } yield ()
```

So far so good. Now, cancelation steps into action in each `.flatMap` in `F`,
that is, in each step of our for-comprehension. If the fiber gets canceled right
before or after the first step, well, that is not an issue. The `offerer` will
be eventually garbage collected, that's all. But what if the cancelation
happens right after the call to `modify`? Well, then `op` will not be run.
Recall that, by the content of `modify`, that `op` can be
`taker.complete(i).void`, `Sync[F].unit` or `offerer.get`. Cancelling after
having removed the `taker` or added the `offerer` to the state but without
running `op` will leave the state inconsistent. We can quickly fix this by
making that code uncancelable:

```scala
def offer[F[_]](i: Int): F[Unit] =
  for {
    offerer <- Deferred[F, Int]
    _       <- F.uncancelable { poll => // `poll` ignored at this point, we'll discuss it later
                 for {
                   op <- stateR.modify {???} // `op` is an F[] to be run
                   _  <- op // `taker.complete(i).void`, `Sync[F].unit` or `offerer.get`
                 } yield ()
              }
  } yield ()
```

What is the problem here? If `op` is non-blocking, that is, if it is either
`F.unit` or `taker.complete(a).void`, then our solution would be ok. But when
the operation is `offerer.get` we have an issue as `.get` will block until
`offerer` is completed (recall it is a `Deferred` instance). So the fiber will
not be able to progress, but at the same time we have set that operation inside
an uncancelable region. So there is no way to cancel that blocked fiber! For
example, we cannot set a timeout on its execution! Thus, if the `offerer` is
never completed then that fiber will never finish.

This can be addressed using `Poll[F]`, which is passed as parameter by
`F.uncancelable`. `Poll[F]` is used to define cancelable code inside the
uncancelable region. So if the operation to run was `offerer.get` we will embed
that call inside the `Poll[F]`, thus ensuring the blocked fiber can be canceled.
Finally, we must also take care of cleaning up the state if there is indeed a
cancelation. That cleaning up will have to remove the `offerer` from the list
of offerers kept in the state, as it shall never be completed. Our `offer`
function has become:

```scala mdoc:compile-only
import cats.effect._
import cats.effect.std.Console
import cats.syntax.all._
import cats.effect.syntax.all._
import scala.collection.immutable.Queue

case class State[F[_], A](queue: Queue[A], capacity: Int, takers: Queue[Deferred[F,A]], offerers: Queue[(A, Deferred[F,Unit])])

def producer[F[_]: Async: Console](id: Int, counterR: Ref[F, Int], stateR: Ref[F, State[F,Int]]): F[Unit] = {

  def offer(i: Int): F[Unit] =
    Deferred[F, Unit].flatMap[Unit]{ offerer =>
      Async[F].uncancelable { poll => // `poll` used to embed cancelable code, i.e. the call to `offerer.get`
        stateR.modify {
          case State(queue, capacity, takers, offerers) if takers.nonEmpty =>
            val (taker, rest) = takers.dequeue
            State(queue, capacity, rest, offerers) -> taker.complete(i).void
          case State(queue, capacity, takers, offerers) if queue.size < capacity =>
            State(queue.enqueue(i), capacity, takers, offerers) -> Async[F].unit
          case State(queue, capacity, takers, offerers) =>
            val cleanup = stateR.update { s => s.copy(offerers = s.offerers.filter(_._2 ne offerer)) }
            State(queue, capacity, takers, offerers.enqueue(i -> offerer)) -> poll(offerer.get).onCancel(cleanup)
        }.flatten
      }
    }

  for {
    i <- counterR.getAndUpdate(_ + 1)
    _ <- offer(i)
    _ <- Async[F].whenA(i % 100000 == 0)(Console[F].println(s"Producer $id has reached $i items"))
    _ <- producer(id, counterR, stateR)
  } yield ()
}
```

The consumer part must deal with cancelation in the same way. It will use
`poll` to enable cancelation on the blocking calls, but at the same time it
will make sure to clean up the state when a cancelation occurs. In this case,
the blocking call is `taker.get`, when such call is canceled the `taker` will
be removed from the list of takers in the state. So our `consumer` is now:

```scala mdoc:compile-only
import cats.effect._
import cats.effect.std.Console
import cats.syntax.all._
import cats.effect.syntax.all._
import scala.collection.immutable.Queue

case class State[F[_], A](queue: Queue[A], capacity: Int, takers: Queue[Deferred[F,A]], offerers: Queue[(A, Deferred[F,Unit])])

def consumer[F[_]: Async: Console](id: Int, stateR: Ref[F, State[F, Int]]): F[Unit] = {

  val take: F[Int] =
    Deferred[F, Int].flatMap { taker =>
      Async[F].uncancelable { poll =>
        stateR.modify {
          case State(queue, capacity, takers, offerers) if queue.nonEmpty && offerers.isEmpty =>
            val (i, rest) = queue.dequeue
            State(rest, capacity, takers, offerers) -> Async[F].pure(i)
          case State(queue, capacity, takers, offerers) if queue.nonEmpty =>
            val (i, rest) = queue.dequeue
            val ((move, release), tail) = offerers.dequeue
            State(rest.enqueue(move), capacity, takers, tail) -> release.complete(()).as(i)
          case State(queue, capacity, takers, offerers) if offerers.nonEmpty =>
            val ((i, release), rest) = offerers.dequeue
            State(queue, capacity, takers, rest) -> release.complete(()).as(i)
          case State(queue, capacity, takers, offerers) =>
            val cleanup = stateR.update { s => s.copy(takers = s.takers.filter(_ ne taker)) }
            State(queue, capacity, takers.enqueue(taker), offerers) -> poll(taker.get).onCancel(cleanup)
        }.flatten
      }
    }

  for {
    i <- take
    _ <- Async[F].whenA(i % 100000 == 0)(Console[F].println(s"Consumer $id has reached $i items"))
    _ <- consumer(id, stateR)
  } yield ()
}
```

We have made our producer-consumer implementation able to handle cancelation.
Notably, we have not needed to change the `producer` and `consumer` functions
signatures. This last implementation is
available
[here](https://github.com/lrodero/cats-effect-tutorial/blob/series/3.x/src/main/scala/catseffecttutorial/producerconsumer/ProducerConsumerBoundedCancelable.scala).

### Exercise: build a concurrent queue
A _concurrent queue_ is, well, a queue data structure that allows safe
concurrent access. That is, several concurrent processes can safely add and
retrieve data from the queue. It is easy to realize that during the previous
sections we already implemented that kind of functionality, it was implemented
by the `take` and `offer` methods embedded in our `producer` and `consumer`
functions. To build a concurrent queue we only need to extract from those
methods the part that handles the concurrent access. As the last exercise we
propose you to implement a concurrent queue that implements the `take` and
`offer` functions, and then rewrite the `producer` and `consumer` to use your
queue. A possible implementation of the queue is given
[here](https://github.com/lrodero/cats-effect-tutorial/blob/series/3.x/src/main/scala/catseffecttutorial/producerconsumer/exerciseconcurrentqueue/Queue.scala),
while the main program using that queue can be found
[here](https://github.com/lrodero/cats-effect-tutorial/blob/series/3.x/src/main/scala/catseffecttutorial/producerconsumer/exerciseconcurrentqueue/Main.scala).

When you are done, take a look to [Queue implementation in cats-effect std
package](https://github.com/typelevel/cats-effect/blob/series/3.x/std/shared/src/main/scala/cats/effect/std/Queue.scala),
you will notice your code is a simplified version of cats-effect own `Queue`!

## Conclusion

With all this we have covered a good deal of what cats-effect has to offer (but
not all!). Now you are ready to use to create code that operate side effects in
a purely functional manner. Enjoy the ride!
