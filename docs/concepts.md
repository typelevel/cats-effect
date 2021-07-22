---
id: concepts
title: Concepts
---

Cats Effect introduces a large number of concepts which, while very general and individually simple, can seem foreign and intimidating if you're new to the space. This also brings along with it a set of terminology for describing concepts and scenarios which can be significantly different from that which is used in other asynchronous frameworks (such as Akka or Vert.x). This page is an attempt to describe the Cats Effect runtime at a high level, introducing the concepts and terminology which is often assumed in discussions about the various details.

As a general orientation, though, Cats Effect should be considered as living in the same conceptual space as any other asynchronous runtime on the JVM. Some other common examples of such runtimes:

- Akka
  + Akka tends to be viewed as a more holistic *framework*, particularly encompassing things such as akka-http and akka-cluster. The elements of Akka which correspond most directly to Cats Effect are `Actor` and `Future` (which is actually part of the Scala standard library).
- Netty
  + Netty's core runtime (`EventLoopGroup` and the various `Handler`s) is relatively basic and mostly just in service of its NIO wrapper, which Cats Effect does not provide, but [Fs2](https://fs2.io) does. Conversely, Cats Effect's runtime is considerably more robust.
- Tokio
- RxJava
- Vert.x

Note that all of the above libraries have significant differences and conceptual mismatches with Cats Effect. Tokio and Vert.x are probably the closest counterparts, though both are more vertically integrated (meaning they take a more *framework* oriented approach), while Cats Effect prescriptively defines itself as a library which enables a broad composable ecosystem. Additionally, in many places, Cats Effect defines features and functionality which simply doesn't exist in these ecosystems. As an example, neither Akka (via `Future`), Netty, nor Vert.x have any support for asynchronous cancelation (also known as "interruption"), meaning that basic functionality such as timeouts and concurrent error handling can result in resource leaks in a running application. Another example of functionality mismatch is the fiber-aware work-stealing runtime, which is present in Tokio and (to a lesser extent) Akka, but not in Netty or Vert.x. As a final example, asynchronous tracing is present to a limited degree in Vert.x and Akka, but absent from all other frameworks, and neither of these provide a version of this functionality which is performant enough for production use (unlike Cats Effect).

Despite the differences, it is generally helpful to understand that Cats Effect fundamentally solves many of the same problems as other frameworks in this space: it is a foundational runtime layer which makes it easy to build and scale complex high-performance asynchronous and parallel software on the JVM and on JavaScript. 

## Fibers

Fibers are *the* fundamental abstraction in Cats Effect. The terminology is intentionally reminiscent of `Thread`s since fibers are literally lightweight threads (often referred to as "green threads" or "coroutines"). Much like threads, they represent a sequence of actions which will ultimately be evaluated in that order by the underlying hardware. Where fibers diverge from threads is in their footprint and level of abstraction.

Fibers are *very* lightweight. The Cats Effect `IO` runtime implements fibers in roughly 150 *bytes* per fiber, meaning that you can literally create tens of millions of fibers within the same process without a problem, and your primary limiting factor will simply be memory. As an example, any client/server application defined using Cats Effect will create a new fiber for each inbound request, much like how a naive server written using Java's `ServerSocket` will create a new `Thread` for each request (except it is both safe and fast to do this with fibers!). Because they are so lightweight, the act of creating and starting a new fiber is extremely fast in and of itself, making it possible to create very short-lived, "one-off" fibers whenever it is convenient to do so. Many of the functions within Cats Effect are implemented in terms of fibers under the surface, even ones which don't involve parallelism (such as `memoize`).

This property alone would be sufficient to make fibers a useful tool, but Cats Effect takes this concept even further by defining first-class support for asynchronous callbacks, resource handling, and cancelation (interruption) for all fibers. The asynchronous support in particular has profound effects, since it means that any individual "step" of a fiber (much like a statement in a thread) may be either *synchronous* in that it runs until it produces a value or errors, or *asynchronous* in that it registers a callback which may be externally invoked at some later point, and there is no fundamental difference between these steps: they're just part of the fiber. This means that it is just as easy to define business logic which weaves through asynchronous, callback-oriented actions as it is to define the same logic in terms of classically blocking control flows.

**Put another way: with fibers, there is no difference between a callback and a `return`.**

Each step in a thread is a *statement*, and those statements are defined in sequence by writing them in a particular order within a text file, combined together using the semicolon (`;`) operator. Each step in a fiber is an *effect*, and those effects are defined in sequence by explicitly composing them using the `flatMap` function. For example:

```scala mdoc:silent
import cats.effect._

IO.println("Hello") flatMap { _ => 
  IO.println("World")
}
```

Alternatively, it is also quite common to use `for`-comprehensions to express the same thing:

```scala mdoc:silent
for {
  _ <- IO.println("Hello")
  _ <- IO.println("World")
} yield ()
```

Since `flatMap` is just a method like any other, rather magic syntax such as `;`, it is possible to build convenience syntax and higher-level abstractions which encode common patterns for composing effects. For example, the pattern where we put together two effects, ignoring the result of the first one, is common enough to merit its own operator: `>>`:

```scala mdoc:silent
IO.println("Hello") >> IO.println("World")
```

(this operator is nearly synonymous with `*>`, which is also commonly used and has the same meaning)

We can even use Scala's macros to build our own convenience syntax on top of `flatMap`. For example, using [typelevel/cats-effect-cps](https://github.com/typelevel/cats-effect-cps), it is possible to define fibers in a fashion reminiscent of the `async`/`await` syntax present in other languages like JavaScript and Rust:

```scala
import cats.effect.cps._

async[IO] {
  IO.println("Hello").await
  IO.println("World").await
}
```

All of these are *fibers*. Or rather, they are definitions of *part* of a fiber, much like a pair of statements defines part of a thread. In fact, `IO.println` itself is defined in terms of `flatMap` and other operations, meaning that it too is part of a fiber. Each step of a fiber is an effect, and composing steps together produces a larger effect, which can in turn continue to be composed.

Every application has a "main fiber". This is very similar to the notion of a "main thread" in that it is the point at which control flow *starts* within the process. Conventionally in Cats Effect, this main fiber is defined using `IOApp`, and in particular by the effect returned by the `run` method:

```scala mdoc:silent
object Hi extends IOApp.Simple {
  val run = IO.println("Hello") >> IO.println("World")
}
```

In this example, `Hi` is a main class. When it is externally invoked (e.g. using the `java` command, or with `node` when using ScalaJS), it will start the main fiber and run until that fiber completes, at which point the process will be shut down.

When one fiber `start`s another fiber, we generally say that the first fiber is the "parent" of the second one. This relationship is not directly hierarchical in that the parent can terminate before the child without causing any inconsistencies. However, certain properties of the fiber model make considerably more sense with this parent/child relationship in mind. For example, fibers may always observe *and recover from* errors (using something like `handleErrorWith` or `attempt`). This is conceptually similar to `try`/`catch`. Fibers may also observe their own cancelation (see below), but they cannot *recover* from it, meaning that they cannot continue executing after cancelation. Parent fibers may initiate cancelation in a child (via the `cancel` method), and can observe the final outcome of that child (which may be `Canceled`) *and* may continue executing after the child has terminated.

### Cancelation

By default, fibers are *cancelable* at all points during their execution. This means that unneeded calculations can be promptly terminated and their resources gracefully released as early as possible within an application.

In practice, fiber cancelation most often happens in response to one of two situations: timeouts and concurrent errors. As an example of the former:

```scala mdoc:silent
import scala.concurrent.duration._

lazy val loop: IO[Unit] = IO.println("Hello, World!") >> loop

loop.timeout(5.seconds)   // => IO[Unit]
```

The above constructs an `IO` which starts a fiber defined by `loop`. This fiber prints "Hello, World!" infinitely to standard out. Left to its own devices, the `loop` fiber will run forever. However, the `timeout` function delays for 5 seconds, after which it calls `cancel` on the fiber, interrupting its execution and freeing any resources it currently holds (in this case, none).

This is very similar in concept to the `Thread#interrupt` method in the Java standard library. Despite the similarity, there are some important differences which make cancelation considerably more robust, more reliable, and *much* safer.

First off, is *cooperative*. When one fiber calls `cancel` on another fiber, it is effectively a request to the target fiber. If the target fiber is unable to cancel at that moment for any reason, the canceling fiber asynchronously waits for cancelation to become possible. Once cancelation starts, the target fiber will run all of its finalizers (usually used to release resources such as file handles) before yielding control back to the canceler. Conversely, `interrupt` always returns immediately, even if the target `Thread` has not actually been interrupted.

Secondarily, cancelation can be suppressed within scoped regions. If a fiber is performing a series of actions which must be executed atomically (i.e. either all actions execute, or none of them do), it can use the `IO.uncancelable` method to mask the cancelation signal within the scope, ensuring that cancelation is deferred until the fiber has completed its critical section. This is commonly used in conjunction with compound resource acquisition, where a scarce resource might leak if the fiber were to be canceled "in the middle". This differs considerably from `Thread#interrupt`, which cannot be suppressed.

Finally, due to the fact that the fiber model offers much more control and tighter guarantees around cancelation, it is possible and safe to dramatically increase the granularity of cancelation within the target fiber. In particular, every *step* of a fiber contains a cancelation check. This is similar to what `interrupt` would do if the JVM checked the interruption flag on every `;`. This is exactly how the `loop` fiber in the example above is canceled despite the fact that the thread never blocks. Anyone who has ever attempted to use `Thread#interrupt` on a `while`-loop understands how important this distinction is: in Cats Effect, canceling this kind of fiber is possible and indeed quite common.

## "Asynchronous" vs "Concurrent" vs "Parallel"

It is quite common in most imperative language contexts (including Scala) to conflate these three terms. "Asynchronous" in particular is often taken to be synonymous with "parallel", when in fact all three have a very distinct definition which becomes significant in frameworks like Cats Effect.

### Asynchronous

In particular, "asynchronous" is the opposite of "synchronous", and it pertains to a manner in which a given effect produces a value. Synchronous effects are defined using `apply` (also `delay` or `blocking` or `interruptible`) and produce their results using `return`, or alternatively raise errors using `throw`. These are the familiar "sequential" type of effects:

```scala mdoc:silent
IO(Thread.sleep(500))   // => IO[Unit]
```

(note: the above should really be defined using `IO.interruptible` since `Thread.sleep` blocks the underlying `Thread`, but we're using `apply` to illustrate the point)

*Asynchronous* effects are defined using `async` (or `async_`) and produce their results using a callback, where a successful result is wrapped in `Right` while an error is wrapped in `Left`:

```scala mdoc:silent
import java.util.concurrent.{Executors, TimeUnit}

val scheduler = Executors.newScheduledThreadPool(1)

IO.async_[Unit] { cb =>
  scheduler.schedule(new Runnable {
    def run = cb(Right(()))
  }, 500, TimeUnit.MILLISECONDS)

  ()
}
// => IO[Unit]
```

Both the `Thread.sleep` and the `schedule` effects shown here have the same semantics: they delay for 500 milliseconds before allowing the next step in the fiber to take place. Where they differ is the fashion in which they were defined: `Thread.sleep` is *synchronous* while `schedule` is *asynchronous*. The implications of this are surprisingly profound. Since `Thread.sleep` does not return JVM-level control flow until after its delay expires, it effectively wastes a scarce resource (the underlying kernel `Thread`) for its full duration, preventing other actions from utilizing that resource more efficiently in the interim. Conversely, `schedule` returns *immediately* when run and simply invokes the callback in the future once the given time has elapsed. This means that the underlying kernel `Thread` is not wasted and can be repurposed to evaluate other work in the interim.

Asynchronous effects are considerably more efficient than synchronous effects (whenever they are applicable, such as for network I/O or timers), but they are generally considered to be harder to work with in real applications due to the need to manually manage callbacks and event listeners. Fibers entirely eliminate this disadvantage due to their built-in support for asynchronous effects. In *both* of the above examples, the effect in question is simply a value of type `IO[Unit]`, and from the outside, both effects behave identically. Thus, the difference between `return`/`throw` and a callback is encapsulated entirely at the definition site, while the rest of your application control flow remains entirely oblivious. This is a large part of the power of fibers.

It is critical to note that nothing in the definition of "asynchronous" implies "parallel" or "simultaneous", nor does it negate the meaning of "sequential" (remember: all fibers are sequences of effects). "Asynchronous" simply means "produces values or errors using a callback rather than `return`/`throw`". It is an implementation detail of an effect, managed by a fiber, rather than a larger fundamental pattern to be designed around.

### Concurrent

In the language of Cats Effect, "concurrent" generally refers to two or more actions which are defined to be independent in their control flow. It is the opposite of "sequential", or rather, "sequential" implies that something *cannot* be "concurrent". Critically, it is possible for things that are "concurrent" to evaluate sequentially if the underlying runtime decides this is optimal, whereas actions which are sequential will always be evaluated one after the other.

Concurrency is often conflated with asynchronous execution due to the fact that, in practice, the *implementation* of concurrency often relies upon some mechanism for asynchronous evaluation. But as noted above, asynchrony is just that: an implementation detail, and one which says nothing about concurrent vs sequential semantics.

Cats Effect has numerous mechanisms for defining concurrent effects. One of the most straightforward of these is `parTupled`, which evaluates a pair of independent effects and produces a tuple of their results:

```scala
(callServiceA(params1), callServiceB(params2)).parTupled   // => IO[(Response, Response)]
```

As with all concurrency support, `parTupled` is a way of *declaring* to the underlying runtime that two effects (`callServiceA(params1)` and `callServiceB(params2)`) are independent and can be evaluated in parallel. Cats Effect will never *assume* that two effects can be evaluated in parallel.

All concurrency in Cats Effect is implemented in terms of underlying primitives which create and manipulate fibers: `start` and `join`. These concurrency primitives are very similar to the equivalently-named operations on `Thread`, but as with most things in Cats Effect, they are considerably faster and safer.

#### Structured Concurrency

Formally-speaking, structured concurrency is a form of control flow in which all concurrent operations must form a closed hierarchy. Conceptually, it means that any operation which *forks* some actions to run concurrently must forcibly ensure that those actions are completed before moving forward. Furthermore, the *results* of a concurrent operation must only be made available upon its completion, and only to its parent in the hierarchy. `parTupled` above is a simple example of this: the `IO[(Response, Response)]` is unavailable as a result until *both* service calls have completed, and those responses are only accessible within the resulting tuple.

Cats Effect has a large number of structured concurrency tools, most notably `parTupled`, `parMapN`, and `parTraverse`. Additionally, it offers a number of more robust structured concurrency operators such as `background`, `Supervisor`, and `Dispatcher`. It has also fostered an ecosystem wherein structured concurrency is the *rule* rather than the exception, particularly with the help of higher-level frameworks such as [Fs2](https://fs2.io). However, structured concurrency can be very limiting, and Cats Effect does not *prevent* unstructured concurrency when it is needed.

In particular, fibers may be `start`ed without the caller being forced to wait for their completion. This low-level flexibility is necessary in some cases, but it is also somewhat dangerous since it can result in fiber "leaks" (in which a fiber is `start`ed and all references to it outside of the runtime are abandoned). It is generally better to rely on structured (but very flexible) tools such as `background` or `Supervisor`.

Additionally, Cats Effect provides a pair of general tools for modeling shared concurrent state, `Ref` and `Deferred`. These mechanisms are fundamentally unstructured in nature, and *can* result in business logic which is unnecessarily difficult to follow. However, as with many powerful tools, they do have a time and place. These tools can be used to create powerful higher-level abstractions such as `Queue`, `Semaphore`, and such.

All of which is to say that Cats Effect *encourages* structured concurrency and provides users with a large number of flexible tools for achieving it, but it does not *prevent* unstructured concurrent compositions such as `start` or `Ref`.

### Parallel

Much like asynchronous execution, parallelism is an implementation detail of the runtime. When two things are evaluated in parallel, it means that the underlying runtime and hardware are free to schedule the associated computations *simultaneously* on the underlying processors. This concept is very related to that of concurrency in that concurrency is how users of Cats Effect declare to the runtime that things *can* be executed in parallel.

It is generally easier to understand the distinction between concurrency and parallelism by examining scenarios in which concurrent effects would *not* be evaluated in parallel. One obvious scenario is when the application is running on JavaScript rather than on the JVM. Since JavaScript is a single-threaded language, it is impossible for *anything* to evaluate in parallel, even when defined to be concurrent. Now, this doesn't mean that concurrency is useless on JavaScript, since it is still helpful for the runtime to understand that it doesn't need to wait for A to finish before it executes B, but it does mean that everything will, on the underlying hardware, evaluate sequentially.

A more complex example of non-parallel evaluation of concurrent effects can happen when the number of fibers exceeds the number of underlying `Thread`s within the runtime. In general, Cats Effect's runtime attempts to keep the number of underlying threads matched to the number of physical threads provided by the hardware, while the number of fibers may grow into the tens of millions (or even higher on systems with a large amount of available memory). Since there are only a small number of actual carrier threads, the runtime will schedule *some* of the concurrent fibers on the same underlying carrier thread, meaning that those fibers will execute in series rather than in parallel.

As another implementation detail, it is worth noting that fibers are prevented from "hogging" their carrier thread, even when the underlying runtime only has a single thread of execution (such as JavaScript). Whenever a fiber sequences an asynchronous effect, it yields its thread to the next fiber in the queue. Additionally, if a fiber has had a long series of sequential effects without yielding, the runtime will detect the situation and insert an artificial yield to ensure that other pending fibers have a chance to make progress. This is one important element of *fairness*.

## Effects

An effect is a description of an action (or actions) that will be taken when evaluation happens. One very common sort of effect is `IO`:

```scala mdoc
val printer: IO[Unit] = IO.println("Hello, World")
val printAndRead: IO[String] = IO.print("Enter your name: ") >> IO.readLine

def foo(str: String): IO[String] = ???
```

In the above snippet, `printer` and `printAndRead` are both effects: they describe an action (or in the case of `printAndRead`, *actions* plural) which will be taken when they are evaluated. `foo` is an example of a function which *returns* an effect. As a short-hand, such functions are often called "effectful": `foo` is an effectful function.

This is very distinct from saying that `foo` is a function which *performs effects*, in the same way that the `printer` effect is very distinct from *actually printing*. This is illustrated neatly if we write something like the following:

```scala
printer
printer
printer
```

When this code is evaluated, the text "Hello, World" will be printed exactly *zero* times, since `printer` is just a descriptive value; it doesn't *do* anything on its own. Cats Effect is all about making it possible to express effects *as values*.

Notably, this is something that `Future` cannot do:

```scala
val printer: Future[Unit] = Future(println("Hello, World"))

printer
printer
printer
```

This will print "Hello, World" exactly once, meaning that `printer` does not represent a *description* of an action, but rather the results of that action (which was already acted upon outside of our control). Critically, swapping `val` for `def` in the above would result in printing three times, which is a source of bugs and unexpected behavior more often than you might expect when working with `Future`.

In advanced usage of Cats Effect, it is also common to use effect types which are not simply `IO`:

```scala mdoc
import cats.Monad
import cats.effect.std.Console
import cats.syntax.all._

def example[F[_]: Monad: Console](str: String): F[String] = {
  val printer: F[Unit] = Console[F].println(str)
  (printer >> printer).as(str)
}
```

In the above, `example` is an effectful function, and `printer` is an effect (as is `printer >> printer` and `(printer >> printer).as(str)`). The effect *type* here is `F`, which might be `IO`, but it also might be something more interesting! The caller of `example` is free to choose the effect at the call site, for example by writing something like `example[IO]("Hello, World")`, which in turn would return an `IO[String]`. Much of the Cats Effect ecosystem's composability is achieved using this technique under the surface.

### Side-Effects

When running a piece of code causes changes outside of just returning a value, we generally say that code "has side-effects". More intuitively, code where you care whether it runs more than once, and/or *when* it runs, almost always has side-effects. The classic example of this is `System.out.println`:

```scala mdoc
def double(x: Int): Int = {
  System.out.println("Hello, World")
  x + x
}
```

The `double` function takes an `Int` and returns that same `Int` added to itself... and it prints "Hello, World" to standard out. This is what is meant by the "side" in "side-effect": something else is being done "on the side". The same thing could be said about logging, changing the value of a `var`, making a network call, etc etc.

Critically, a *side-effect* is not the same thing as an *effect*. An effect is a *description* of some action, where the action may perform side-effects when executed. The fact that effects are just descriptions of actions is what makes them much safer and more controllable. When a piece of code contains a side-effect, that action just *happens*. You can't make it evaluate in parallel, or on a different thread pool, or on a schedule, or make it retry if it fails. Since an effect is just a description of what actions to take, can freely change the semantics of how it eventually executes to meet the needs of your specific use-case.

In Cats Effect, code containing side-effects should always be wrapped in one of the "special" constructors. In particular:

- **Synchronous** (`return`s or `throw`s)
  + `IO(...)` or `IO.delay(...)`
  + `IO.blocking(...)`
  + `IO.interruptible(true/false)(...)`
- **Asynchronous** (invokes a callback)
  + `IO.async` or `IO.async_`

When side-effecting code is wrapped in one of these constructors, the code itself still contains side-effects, but outside the lexical scope of the constructor we can reason about the whole thing (e.g. including the `IO(...)`) as an effect, rather than as a side-effect.

For example, we can wrap the `System.out.println` side-effecting code from earlier to convert it into an effect value:

```scala mdoc
val wrapped: IO[Unit] = IO(System.out.println("Hello, World"))
```

Being strict about this rule of thumb and always wrapping your side-effecting logic in effect constructors unlocks all of the power and composability of functional programming. This also makes it possible for Cats Effect to do a more effective job of scheduling and optimizing your application, since it can make more aggressive assumptions about when to evaluate pieces of code in ways that better utilize CPU and cache resources.
