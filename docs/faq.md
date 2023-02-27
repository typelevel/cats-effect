---
id: faq
title: FAQ
---

## Scala CLI

[Scala CLI](https://scala-cli.virtuslab.org/) can run both `.sc` files and `.scala` files. `.sc` files allow definitions at the top level and a main method is synthesized to run it. Unfortunately this does not work well with `IO#unsafeRunSync`. You should put your cats-effect code inside the `run` method of an `IOApp` and save it as a `.scala` file instead.

```scala-cli
//> using scala "2.13.8"
//> using lib "org.typelevel::cats-effect::3.4.8"

import cats.effect._

object HelloWorld extends IOApp.Simple {
  val run: IO[Unit] = IO.println("Hello world")
}
```

```sh
$ scala-cli Hello.scala
Hello world
```

### Native Image Example

[Scala CLI](https://scala-cli.virtuslab.org/) can be leveraged to produce a native-image executable using the [package command](https://scala-cli.virtuslab.org/docs/commands/package#native-image):

```sh
$ scala-cli package --native-image --graalvm-version 22.1.0 -f Hello.scala -- --no-fallback
[...]
$ ./HelloWorld
Hello world
```

> Note: GraalVm Native Image > 21.0.0 and `--no-fallback` are mandatory: see [here](core/native-image.md) for details

### Scala Native Example

[Scala CLI](https://scala-cli.virtuslab.org/) can be leveraged to produce a [Scala Native](https://github.com/scala-native/scala-native) executable using the [package command](https://scala-cli.virtuslab.org/docs/commands/package/#scala-native):

```sh
$ scala-cli package --native Hello.scala
[...]
$ ./HelloWorld
Hello world
```

See [here](core/scala-native.md) for details.

## How can I loop over a collection inside of `IO`?

`traverse`!

The `traverse` method is, in some sense, Cats' equivalent of `foreach`, but much more powerful in that it allows each iteration of the loop to be wrapped in an effect (such as `IO`) and can produce a value. This also allows it to generalize nicely to run operations in parallel, with the `parTraverse` method.

```scala mdoc:reset:silent
import cats.effect._
import cats.syntax.all._

val names = List("daniel", "chris", "joseph", "renee", "bethany", "grace")
val program: IO[List[Unit]] = names.traverse(name => IO.println(s"Hi, $name!"))
```

In the above example, `traverse` will iterate over every element of the `List`, running the function `String => IO[Unit]` which it was passed, assembling the results into a resulting `List` wrapped in a single outer `IO`.

> You *must* have `import cats.syntax.all_` (or `import cats.syntax.traverse._`) in scope, otherwise this will not work! `traverse` is a method that is implicitly enriched onto collections like `List`, `Vector`, and such, meaning that it must be brought into scope using an import (unfortunately!).

Of course, in the above example, we don't really care about the results, since our `IO` is constantly producing `Unit`. This pattern is so common that we have a special combinator for it: `traverse_`.

```scala mdoc:reset:silent
import cats.effect._
import cats.syntax.all._

val names = List("daniel", "chris", "joseph", "renee", "bethany", "grace")
val program: IO[Unit] = names.traverse_(name => IO.println(s"Hi, $name!"))
```

This efficiently discards the results of each `IO` (which are all `()` anyway), producing a single `IO[Unit]` at the end.

This type of pattern generalizes extremely well, both to actions which *do* return results, as well as to more advanced forms of execution, such as parallelism. For example, let's define and use a `fetchUri` method which uses [Http4s](https://http4s.org) to load the contents of a URI. We will then iterate over our list of `names` *in parallel*, fetching all of the contents into a single data structure:

```scala
import cats.effect._
import cats.syntax.all_

import org.http4s.ember.client.EmberClientBuilder

// just as an example...
val Prefix = "https://www.scb.se/en/finding-statistics/sverige-i-siffror/namesearch/Search/?nameSearchInput="
val Extract = """(\d+(?:\s*\d+)*) persons""".r

val names = List("daniel", "chris", "joseph", "renee", "bethany", "grace")

val program: IO[Unit] = EmberClientBuilder.default[IO].build use { client =>
  def fetchUri(uri: String): IO[String] = client.expect[String](uri)

  val counts: List[(String, Int)] = names parTraverse { name => 
    fetchUri(Prefix + name) map { body =>
      val Extract(countStr) = body  // who needs html parsing?
      name -> countStr.toInt
    }
  }

  counts.traverse_(IO.println(_))
}
```

The above `program` creates a new HTTP client (with associated connection pooling and other production configurations), then *in parallel* iterates over the list of names, constructs a search URL for each name, runs an HTTP `GET` on that URL, "parses" the contents using a naive regular expression extracting the count of persons who have that given name, and produces the results of the whole process as a `List[(String, Int)]`. Then, given this list of `counts`, we *sequentially* iterate over the results and print out each tuple. Finally, we fully clean up the client resources (e.g. closing the connection pool). If any of the HTTP `GET`s produces an error, all resources will be closed and the remaining connections will be safely terminated.

In general, any time you have a problem where you have a collection, `Struct[A]` and an *effectful* function `A => IO[B]` and your goal is to get an `IO[Struct[B]]`, the answer is going to be `traverse`. This works for almost any definition of `Struct` that you can think of! For example, you can do this for `Option[A]` as well. You can even enable this functionality for custom structures by defining an instance of the [`Traverse` typeclass](https://typelevel.org/cats/typeclasses/traverse.html) for your custom datatype (hint: most classes or algebras which have a shape like `Struct[A]` where they *contain a value of type `A`* can easily implement `Traverse`).

## Why is my `IO(...)` running on a blocking thread?

Cats Effect guarantees that `IO.blocking(...)` will run on a blocking thread. However, in many situations it may also run a non-blocking operation `IO(...)` (or `IO.delay(...)`) on a blocking thread. Do not be alarmed! This is an optimization.

Consider the following program:

```scala
IO.blocking(...) *> IO(...) *> IO.blocking(...)
```

If the `IO(...)` in the middle was run on the compute pool this would require:

1. requisitioning a blocking thread, and running the first blocking op
2. shifting back to the compute pool, for the non-blocking op
3. requisitioning another blocking thread, for the second blocking op

So in this case, the intermediate `IO(...)` is highly likely to run on a blocking thread. This enables the entire sequence to run on the same blocking thread, without any shifting. As soon as any asynchronous operation (or an auto-cede boundary) is encountered, the fiber will be shifted back to a compute thread. The tradeoff here is to create a small amount of unnecessary thread contention in exchange for avoiding unnecessary context shifts in many cases.

Note that this is just a specific example to demonstrate why running an non-blocking `IO(...)` on a blocking thread can be beneficial, but it is not the only situation in which you may observe this behavior.

## Dealing with Starvation

Cats Effect 3.4.0 introduced a default-enabled *starvation checker*, which produces warnings like the following:

```
2023-01-28T00:16:24.101Z [WARNING] Your app's responsiveness to a new asynchronous 
event (such as a new connection, an upstream response, or a timer) was in excess
of 40 milliseconds. Your CPU is probably starving. Consider increasing the 
granularity of your delays or adding more cedes. This may also be a sign that you
are unintentionally running blocking I/O operations (such as File or InetAddress)
without the blocking combinator.
```

If you're seeing this warning and have not changed any of the default configurations, it means that your application is taking at least *100 milliseconds* to respond to an external asynchronous event. In this case, the runtime is measuring this fact using a timer, but external events are also things such as new connections, request bodies, upstream responses, and such. In other words, **if you're seeing this warning, it means your response latencies are *at least* this long.**

- If this level of application performance is not within acceptable bounds, please see the [starvation and tuning](core/starvation-and-tuning.md) documentation for more discussion on how you can resolve the issue
- If 100 milliseconds is acceptable for your use-case, but you would still like to preserve checking with some higher time-bound, you can adjust this by overriding the `cpuStarvationCheckInterval` (default: `1.second`) in `IORuntimeConfig`. The threshold coefficient itself (default: `0.1d`) is configurable via `cpuStarvationCheckThreshold`, but it is generally best practice to tune the interval rather than the threshold. Increasing the interval increases the absolute threshold proportionally (e.g. setting the interval to `2.seconds` would change the warning cutoff to 200 milliseconds)
- If you would like to entirely disable this check, you can do so by overriding the `cpuStarvationCheckInitialDelay` value within `IORuntimeConfig` to `Duration.Inf`

Please understand that this warning is essentially never a false negative. Outside of some extremely rare circumstances, it accurately measures the responsiveness of your application runtime. However, as a corollary of this accuracy, it is measuring not only your application runtime, but also the system (and other adjacent processes on that system) on which your application is running. Thus, if you see this warning, it essentially always means that either the checker's definition of "responsiveness" (100 millisecond SLA) is different from what you expected, or it means that there is some legitimate problem *somewhere* in your application, your JVM, your kernel, your host environment, or all of the above.
