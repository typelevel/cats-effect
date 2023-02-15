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
    Supervisor[IO](await = true).use { supervisor =>
      HttpServer(handler(supervisor)).resource.use(_ => IO.never)
    }

}

```

In this example, `longRunningTask` is started in the background.
The server returns to the client without waiting for the task to finish.
