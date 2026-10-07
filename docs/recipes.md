---
id: recipes
title: Recipes
---

## Start a supervised task that outlives the creating scope

If you need to run an action in a fiber in a "start-and-forget" manner, you'll want to use [Supervisor](std/supervisor.md). 
This lets you safely evaluate an effect in the background without waiting for it to complete and ensuring that the fiber and all its resources are cleaned up at the end.
You can configure a`Supervisor` to  wait for all supervised fibers to complete at the end its lifecycle, or to simply cancel any remaining active fibers.

Here is a very simple example of `Supervisor` telling a joke:

```scala mdoc:silent
import scala.concurrent.duration._

import cats.effect.{IO, IOApp}
import cats.effect.std.Supervisor

object Joke extends IOApp.Simple {

  val run =
    Supervisor[IO](await = true).use { supervisor =>
      for {
        _ <- supervisor.supervise(IO.sleep(50.millis) >> IO.print("MOO!"))
        _ <- IO.println("Q: Knock, knock!")
        _ <- IO.println("A: Who's there?")
        _ <- IO.println("Q: Interrupting cow.")
        _ <- IO.print("A: Interrupting cow") >> IO.sleep(50.millis) >> IO.println(" who?")
      } yield ()
    }

}
```

This should print:

```
Q: Knock, knock!
A: Who's there?
Q: Interrupting cow.
A: Interrupting cowMOO! who?
```

Here is a more practical example of `Supervisor` using a simplified model of an HTTP server:

```scala mdoc:invisible:reset-object
import scala.concurrent.duration._

import cats.effect._

final case class Request(path: String, paramaters: Map[String, List[String]])

sealed trait Response extends Product with Serializable
case object NotFound extends Response
final case class Ok(payload: String) extends Response

// dummy case class representing the bound server
final case class IpAddress()

// an HTTP server is a function from request to IO[Response] and is managed within a Resource
final case class HttpServer(handler: Request => IO[Response]) {
  def resource: Resource[IO, IpAddress] = Resource.eval(IO.never.as(IpAddress()))
}

val longRunningTask: Map[String, List[String]] => IO[Unit] = _ => IO.sleep(10.minutes)
```


```scala mdoc:silent
import cats.effect.{IO, IOApp}
import cats.effect.std.Supervisor

object Server extends IOApp.Simple {

  def handler(supervisor: Supervisor[IO]): Request => IO[Response] = {
    case Request("start", params) => 
      supervisor.supervise(longRunningTask(params)).void >> IO.pure(Ok("started"))
    case Request(_, _) => IO.pure(NotFound)
  }

  val run =
    Supervisor[IO](await = true).flatMap { supervisor =>
      HttpServer(handler(supervisor)).resource
    }.useForever

}

```

In this example, `longRunningTask` is started in the background.
The server returns to the client without waiting for the task to finish.

## Atomically update a Ref with result of an effect

Cats Effect provides [Ref](std/ref.md), that we can use to model mutable concurrent reference. 
However, if we want to update our ref using result of an effect `Ref` will usually not be enough and we need a more powerful construct to achieve that. 
In cases like that we can use the [AtomicCell](std/atomic-cell.md) that can be viewed as a synchronized `Ref`.

The most typical example for `Ref` is the concurrent counter, but what if the update function for our counter would be effectful?

Assume we have the following function:

```scala mdoc:silent
def update(input: Int): IO[Int] =
  IO(input + 1)
```
If we want to concurrently update a variable using our `update` function it's not possible to do it directly with `Ref`, luckily `AtomicCell` has `evalUpdate`:

```scala mdoc:silent
import cats.effect.std.AtomicCell

class Server(atomicCell: AtomicCell[IO, Int]) {
  def update(input: Int): IO[Int] =
    IO(input + 1)

  def performUpdate(): IO[Int] =
    atomicCell.evalGetAndUpdate(i => update(i))
}
```

To better present real-life use-case scenario, let's imagine that we have a `Service` that performs some HTTP request to external service holding exchange rates over time:

```scala mdoc:silent
import cats.effect.std.Random

case class ServiceResponse(exchangeRate: Double)

trait Service {
  def query(): IO[ServiceResponse]
}

object StubService extends Service {
  override def query(): IO[ServiceResponse] = Random
    .scalaUtilRandom[IO]
    .flatMap(random => random.nextDouble)
    .map(ServiceResponse(_))
}
```
To simplify we model the `StubService` to just return some random `Double` values.

Now, say that we want to have a cache that holds the highest exchange rate that is ever returned by our service, we can have the proxy implementation based on `AtomicCell` like below:

```scala mdoc:silent
class MaxProxy(atomicCell: AtomicCell[IO, Double], requestService: Service) {

  def queryCache(): IO[ServiceResponse] = 
    atomicCell evalModify { current =>
      requestService.query() map { result =>
        if (result.exchangeRate > current)
          (result.exchangeRate, result)
        else
          (current, result)
      }
    }
  
  def getHistoryMax(): IO[Double] = atomicCell.get
}


```

## Handle multiple callbacks from an unsafe API

Some libraries deliver their results by invoking a callback once per event, on their own threads: message consumers, WebSockets, sensors, GUI toolkits, and so on. The callback is an impure function returning `Unit`, so it cannot run our effectful handling code itself. The standard tool for this situation is a [Dispatcher](std/dispatcher.md), which runs effects on behalf of impure code. Combined with a [Queue](std/queue.md), which buffers the events and decouples their production from their consumption, we can expose such a library as an effectful stream of events.

Say we are integrating with a market data feed which invokes a callback for every tick it receives:

```scala mdoc:invisible:reset-object
final case class MarketTick(symbol: String, price: Double)

// a hypothetical third-party library which invokes our callback once per tick, on its own threads
trait TickSource {
  def subscribe(onTick: MarketTick => Unit): AutoCloseable
}
```

The callback offers each tick to a queue (through a `Dispatcher`), and our own fibers consume the queue, so the handling code stays fully effectful:

```scala mdoc:silent
import cats.effect.{IO, Resource}
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all._

def ticks(source: TickSource): Resource[IO, Queue[IO, MarketTick]] =
  Dispatcher.parallel[IO].flatMap { dispatcher =>
    Resource.eval(Queue.unbounded[IO, MarketTick]).flatMap { queue =>
      val subscribe = IO(source.subscribe { tick =>
        dispatcher.unsafeRunAndForget(queue.offer(tick))
      })
      Resource.make(subscribe)(registration => IO(registration.close())).as(queue)
    }
  }

def consume(source: TickSource): IO[Unit] =
  ticks(source).use { queue =>
    queue.take.flatMap(tick => IO.println(s"${tick.symbol}: ${tick.price}")).foreverM
  }
```

Closing the resource scope unsubscribes from the source, cancels any submission still in flight, and drops whatever is left in the queue. A callback that races with that unsubscribe can still reach a `Dispatcher` that has already shut down, and the `unsafeRunAndForget` it calls then throws `IllegalStateException` on the calling thread — the thread of the library invoking the callback. A source that cannot rule out such a race should catch that exception in its callback.

Only the `queue.offer` of each tick goes through the `Dispatcher`; the ticks themselves are handled by the single consumer fiber, in the order they leave the queue. A parallel `Dispatcher` does not guarantee the order in which submitted effects run, so the offers, and therefore the handling, may follow a different order than the callbacks arrived in. If arrival order matters, use `Dispatcher.sequential`, which runs submitted effects strictly in submission order; here the submitted effect is a non-blocking offer, so that choice costs nothing. See the [Dispatcher](std/dispatcher.md) and [Queue](std/queue.md) pages for more details.

## Guarantee exclusive access to a resource

When several fibers share a resource, for example a printer, a file, or a device, it is often necessary to guarantee that only one of them uses it at a time. [Mutex](std/mutex.md) provides exactly this: a fiber that acquires its lock blocks every other fiber until it releases it.

Consider a printer which should never print two jobs at the same time:

```scala mdoc:invisible:reset-object
final case class PrintJob(id: Int, pages: Int)
```

```scala mdoc:silent
import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.std.Mutex

final class SharedPrinter(mutex: Mutex[IO]) {

  def print(job: PrintJob): IO[Unit] =
    mutex.lock.surround {
      for {
        _ <- IO.println(s"job ${job.id}: printing ${job.pages} pages")
        _ <- IO.sleep(100.millis) // simulate slow printing
        _ <- IO.println(s"job ${job.id}: done")
      } yield ()
    }
}
```

Jobs can now be submitted from any number of fibers, and each job runs to completion before the next one starts, so their pages never interleave:

```scala mdoc:silent
import cats.syntax.all._

def submitAll(printer: SharedPrinter, jobs: List[PrintJob]): IO[Unit] =
  jobs.parTraverse_(printer.print)
```

For the two jobs `PrintJob(1, 10)` and `PrintJob(2, 5)` the output is fully serialized. Which job runs first depends on whoever acquires the lock first — the block below shows one of the two possible orders — but the jobs never overlap:

```
job 1: printing 10 pages
job 1: done
job 2: printing 5 pages
job 2: done
```

Note that a `Mutex` is not reentrant: a fiber that acquires the lock while already holding it will deadlock. If more than one fiber at a time may use the resource, use a [Semaphore](std/semaphore.md) instead; for resources shared per key, use [KeyedMutex](std/keyed-mutex.md). See the [Mutex](std/mutex.md) page for more details.

## Replace `Kleisli` with `IOLocal`

Some applications need to make a context available to all effects: a trace id, the current user, or the request being handled. A natural encoding is `Kleisli[IO, Ctx, A]`, but then every function that reads the context must be lifted into `Kleisli`, and every call site must thread it through. [IOLocal](core/io-local.md) offers an alternative: a value which every fiber carries along with it, readable from any effect without changing any signature.

Say we want every log line of a request to be tagged with its request id:

```scala mdoc:invisible:reset-object
final case class Ctx(requestId: String)
```

```scala mdoc:silent
import cats.effect.{IO, IOLocal}

class RequestHandler(local: IOLocal[Ctx]) {

  // no Ctx parameter anywhere: the context is read from the ambient IOLocal
  def handle(request: String): IO[Unit] =
    local.get.flatMap { ctx =>
      IO.println(s"[${ctx.requestId}] handling $request")
    }
}

def serve(
    local: IOLocal[Ctx],
    handler: RequestHandler
)(requestId: String, request: String): IO[Unit] =
  local.set(Ctx(requestId)) >> handler.handle(request)
```

Every request is handled on its own fiber, and a forked fiber receives a copy of the context at the time of the fork. Setting the context at the start of a request therefore affects that request only:

```scala mdoc:silent
import cats.syntax.all._

def app: IO[Unit] =
  IOLocal(Ctx("unset")).flatMap { local =>
    val handler = new RequestHandler(local)
    List("request-1", "request-2").parTraverse_ { id =>
      serve(local, handler)(id, "GET /users")
    }
  }
```

Both requests run concurrently, yet each log line is tagged with its own request id, never with another request's id or with the default. This is the key difference from a shared `Ref`: a forked fiber operates on a copy of the context, so concurrent requests never observe each other's modifications, and a parent never sees the modifications of its children.

A few cautions apply. An `IOLocal` is not a `Ref`: it abides different laws, so do not use it for shared state. It is specific to `IO`: code that is polymorphic in the effect type can use `IOLocal#asLocal` to get a cats-mtl `Local` backed by it. See [IOLocal](core/io-local.md) for the precise semantics and for interoperability with the JDK `ThreadLocal` API.
