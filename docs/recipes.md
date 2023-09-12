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

