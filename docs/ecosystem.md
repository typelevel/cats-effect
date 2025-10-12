---
id: ecosystem
title: Ecosystem
---

Cats Effect integrates seamlessly with a rich ecosystem of libraries and frameworks. This page provides quick-start guides and examples for common use cases.

## HTTP Clients and Servers

### HTTP4s

HTTP4s is the most popular HTTP library for Cats Effect, providing both client and server capabilities.

#### Server Example

```scala mdoc:silent
import cats.effect.{IO, IOApp, ExitCode}
import cats.syntax.all._
import scala.concurrent.duration._

// Conceptual example - HTTP4s server structure
object Http4sServer extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    // In a real HTTP4s application, you would:
    // 1. Define routes using HttpRoutes.of[IO]
    // 2. Use BlazeServerBuilder to create the server
    // 3. Bind to a port and start serving
    
    IO.println("HTTP4s server would start here") *>
    IO.sleep(100.millis) *>
    IO.pure(ExitCode.Success)
  }
}
```

#### Client Example

```scala mdoc:silent
import cats.effect.IO
import scala.concurrent.duration._

// Conceptual example - HTTP4s client structure
object Http4sClient {
  def fetchData: IO[String] = {
    // In a real HTTP4s application, you would:
    // 1. Create a client using BlazeClientBuilder
    // 2. Use the client to make HTTP requests
    // 3. Parse responses
    
    IO.println("Making HTTP request...") *>
    IO.sleep(100.millis) *>
    IO.pure("Response data")
  }
}
```

#### SBT Dependencies

```scala
libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-blaze-server" % "0.23.18",
  "org.http4s" %% "http4s-blaze-client" % "0.23.18",
  "org.http4s" %% "http4s-dsl" % "0.23.18"
)
```

## Database Access

### Doobie

Doobie provides pure functional database access for Scala.

#### Example Usage

```scala mdoc:silent
import cats.effect.IO
import scala.concurrent.duration._

// Conceptual example - Doobie database operations
object DatabaseExample {
  def createUser(name: String, email: String): IO[String] = {
    // In a real Doobie application, you would:
    // 1. Create a Transactor using H2Transactor or similar
    // 2. Write SQL queries using sql"..." interpolator
    // 3. Execute queries using transactor.run()
    
    IO.println(s"Creating user: $name ($email)") *>
    IO.sleep(50.millis) *>
    IO.pure("User created successfully")
  }
  
  def getUserById(id: Long): IO[Option[String]] = {
    IO.println(s"Fetching user with ID: $id") *>
    IO.sleep(50.millis) *>
    IO.pure(Some("User data"))
  }
}
```

#### SBT Dependencies

```scala
libraryDependencies ++= Seq(
  "org.tpolecat" %% "doobie-core" % "1.0.0-RC2",
  "org.tpolecat" %% "doobie-h2" % "1.0.0-RC2",
  "org.tpolecat" %% "doobie-hikari" % "1.0.0-RC2"
)
```

## Message Queues

### FS2 Kafka

FS2 Kafka provides functional streaming for Apache Kafka.

#### Producer Example

```scala mdoc:silent
import cats.effect.IO
import scala.concurrent.duration._

// Conceptual example - Kafka producer
object KafkaProducer {
  def sendMessages(topic: String, messages: List[String]): IO[Unit] = {
    // In a real FS2 Kafka application, you would:
    // 1. Create ProducerSettings
    // 2. Use Producer.make to create a producer
    // 3. Use Stream.emits to send messages
    
    IO.println(s"Sending ${messages.length} messages to topic: $topic") *>
    messages.traverse(msg => 
      IO.println(s"Sending: $msg") *> IO.sleep(10.millis)
    ).void
  }
}
```

#### Consumer Example

```scala mdoc:silent
import cats.effect.IO
import scala.concurrent.duration._

// Conceptual example - Kafka consumer
object KafkaConsumer {
  def consumeMessages(topic: String): IO[List[String]] = {
    // In a real FS2 Kafka application, you would:
    // 1. Create ConsumerSettings
    // 2. Use KafkaConsumer.stream to create a stream
    // 3. Process CommittableConsumerRecord messages
    
    IO.println(s"Consuming messages from topic: $topic") *>
    IO.sleep(100.millis) *>
    IO.pure(List("Message 1", "Message 2", "Message 3"))
  }
}
```

#### SBT Dependencies

```scala
libraryDependencies ++= Seq(
  "com.github.fd4s" %% "fs2-kafka" % "2.5.0"
)
```

## Configuration Management

### PureConfig

PureConfig provides type-safe configuration loading.

#### Example Usage

```scala mdoc:silent
import cats.effect.IO

// Conceptual example - Configuration loading
case class AppConfig(
  database: DatabaseConfig,
  server: ServerConfig
)

case class DatabaseConfig(url: String, user: String, password: String)
case class ServerConfig(port: Int, host: String)

object ConfigExample {
  def loadConfig: IO[AppConfig] = {
    // In a real PureConfig application, you would:
    // 1. Use ConfigSource.default.load[AppConfig]
    // 2. Handle configuration errors appropriately
    
    IO.pure(AppConfig(
      DatabaseConfig("jdbc:h2:mem:test", "sa", ""),
      ServerConfig(8080, "localhost")
    ))
  }
}
```

#### SBT Dependencies

```scala
libraryDependencies ++= Seq(
  "com.github.pureconfig" %% "pureconfig" % "0.17.4"
)
```

## Testing

### MUnit with Cats Effect

MUnit provides excellent support for testing Cats Effect applications.

#### Example Test

```scala mdoc:silent
import cats.effect.IO

// Conceptual example - Testing with MUnit
class MyServiceSuite {
  def testService(): IO[Unit] = {
    // In a real MUnit test, you would:
    // 1. Extend CatsEffectSuite
    // 2. Use test("description") { ... }
    // 3. Use IO assertions
    
    val service = new MyService()
    
    for {
      result <- service.doSomething()
      _ <- IO.println(s"Test result: $result")
    } yield ()
  }
}

class MyService {
  def doSomething(): IO[String] = 
    IO.println("Doing something") *> IO.pure("Success")
}
```

#### SBT Dependencies

```scala
libraryDependencies ++= Seq(
  "org.scalameta" %% "munit" % "0.7.29",
  "org.typelevel" %% "munit-cats-effect-3" % "1.0.7"
)
```

## Logging

### Log4Cats

Log4Cats provides structured logging for functional applications.

#### Example Usage

```scala mdoc:silent
import cats.effect.IO

// Conceptual example - Structured logging
object LoggingExample {
  def logExample(): IO[Unit] = {
    // In a real Log4Cats application, you would:
    // 1. Create a Logger using Slf4jLogger.getLogger[IO]
    // 2. Use structured logging methods like info, warn, error
    // 3. Include context and structured data
    
    IO.println("INFO: Application started") *>
    IO.println("WARN: This is a warning message") *>
    IO.println("ERROR: This is an error message")
  }
}
```

#### SBT Dependencies

```scala
libraryDependencies ++= Seq(
  "org.typelevel" %% "log4cats-slf4j" % "2.6.0"
)
```

## Metrics and Monitoring

### Prometheus Integration

Prometheus metrics can be easily integrated with Cats Effect applications.

#### Example Usage

```scala mdoc:silent
import cats.effect.IO
import scala.concurrent.duration._

// Conceptual example - Metrics collection
object MetricsExample {
  def collectMetrics(): IO[Unit] = {
    // In a real Prometheus application, you would:
    // 1. Create Counter and Histogram instances
    // 2. Increment counters and record durations
    // 3. Expose metrics via HTTP endpoint
    
    IO.println("Collecting metrics...") *>
    IO.sleep(50.millis) *>
    IO.println("Metrics collected")
  }
}
```

#### SBT Dependencies

```scala
libraryDependencies ++= Seq(
  "io.prometheus" % "simpleclient" % "0.16.0",
  "io.prometheus" % "simpleclient_httpserver" % "0.16.0"
)
```

## Stream Processing

### FS2 Streams

FS2 provides powerful stream processing capabilities.

#### Example Usage

```scala mdoc:silent
import cats.effect.IO
import scala.concurrent.duration._

// Conceptual example - Stream processing
object StreamExample {
  def processData(): IO[List[String]] = {
    // In a real FS2 application, you would:
    // 1. Use Stream.range, Stream.eval, etc.
    // 2. Use combinators like map, filter, evalMap
    // 3. Compile streams to IO
    
    val data = (1 to 10).toList.map(_.toString)
    
    IO.println("Processing stream data...") *>
    data.traverse(item => 
      IO.println(s"Processing: $item") *> IO.sleep(10.millis)
    ).map(_ => data)
  }
}
```

#### SBT Dependencies

```scala
libraryDependencies ++= Seq(
  "co.fs2" %% "fs2-core" % "3.7.0"
)
```

## Getting Started

To get started with any of these libraries:

1. **Add the SBT dependencies** to your `build.sbt`
2. **Import the necessary modules** in your Scala files
3. **Follow the library-specific documentation** for detailed usage
4. **Start with simple examples** and gradually build complexity

## Additional Resources

- [HTTP4s Documentation](https://http4s.org/)
- [Doobie Documentation](https://tpolecat.github.io/doobie/)
- [FS2 Documentation](https://fs2.io/)
- [PureConfig Documentation](https://pureconfig.github.io/)
- [MUnit Documentation](https://scalameta.org/munit/)
- [Log4Cats Documentation](https://log4cats.github.io/log4cats/)

Each of these libraries is designed to work seamlessly with Cats Effect, providing a consistent functional programming experience across your entire application stack.