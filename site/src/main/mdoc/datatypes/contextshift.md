---
layout: docsplus
title:  "ContextShift"
number: 11
source: "shared/src/main/scala/cats/effect/ContextShift.scala"
scaladoc: "#cats.effect.ContextShift"
---

`ContextShift` is the pure equivalent to:
 
- Scala's `ExecutionContext`
- Java's [Executor](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/Executor.html)
- JavaScript's [setTimeout(0)](https://developer.mozilla.org/en-US/docs/Web/API/WindowOrWorkerGlobalScope/setTimeout)
  or [setImmediate](https://developer.mozilla.org/en-US/docs/Web/API/Window/setImmediate)

It provides the means to do 
[cooperative yielding](https://en.wikipedia.org/wiki/Cooperative_multitasking), 
or on top of the JVM to switch thread-pools for execution of blocking operations
or other actions that are problematic.
  
The interface looks like this:

```scala mdoc:silent
import scala.concurrent.ExecutionContext

trait ContextShift[F[_]] {

  def shift: F[Unit]

  def evalOn[A](ec: ExecutionContext)(f: F[A]): F[A]
}
```

Important: this is NOT a type class, meaning that there is no coherence restriction. 
This is because the ability to customize the thread-pool used for `shift` is 
essential on top of the JVM at least.

## shift

The `shift` operation is an effect that triggers a logical fork.

For example, say we wanted to ensure that the current thread
isn't occupied forever on long running operations, we could do
something like this:

```scala mdoc:reset:silent
import cats.effect._
import cats.implicits._

def fib[F[_]](n: Int, a: Long = 0, b: Long = 1)
  (implicit F: Sync[F], cs: ContextShift[F]): F[Long] = {

  F.suspend {
    val next = 
      if (n > 0) fib(n - 1, b, a + b)
      else F.pure(a)
    
    // Triggering a logical fork every 100 iterations
    if (n % 100 == 0)
      cs.shift *> next
    else
      next  
  }
}
```

## evalOn

The `evalOn` operation is about executing a side effectful operation on 
a specific `ExecutionContext`, but then "return" to the "default"
thread-pool or run-loop for the bind continuation.

```scala mdoc:silent
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import cats.effect._

def blockingThreadPool[F[_]](implicit F: Sync[F]): Resource[F, ExecutionContext] =
  Resource(F.delay {
    val executor = Executors.newCachedThreadPool()
    val ec = ExecutionContext.fromExecutor(executor)
    (ec, F.delay(executor.shutdown()))
  })
  
def readName[F[_]](implicit F: Sync[F]): F[String] = 
  F.delay {
    println("Enter your name: ")
    scala.io.StdIn.readLine()
  }

object MyApp extends IOApp {

  def run(args: List[String]) = {
    val name = blockingThreadPool[IO].use { ec =>
      // Blocking operation, executed on special thread-pool
      contextShift.evalOn(ec)(readName[IO])
    }
    
    for {
      n <- name
      _ <- IO(println(s"Hello, $n!"))
    } yield ExitCode.Success
  }
}
```

## Blocker

`Blocker` provides an `ExecutionContext` that is intended for executing blocking tasks and integrates directly with `ContextShift`. The previous example with `Blocker` looks like this:

```scala mdoc:reset:silent
import cats.effect._

def readName[F[_]: Sync: ContextShift](blocker: Blocker): F[String] = 
  // Blocking operation, executed on special thread-pool
  blocker.delay {
    println("Enter your name: ")
    scala.io.StdIn.readLine()
  }

object MyApp extends IOApp {

  def run(args: List[String]) = {
    val name = Blocker[IO].use { blocker =>
      readName[IO](blocker)
    }

    for {
      n <- name
      _ <- IO(println(s"Hello, $n!"))
    } yield ExitCode.Success
  }
}
```

In this version, `Blocker` was passed as an argument to `readName` to ensure the constructed task is never used on a non-blocking execution context.
