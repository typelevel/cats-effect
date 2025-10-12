---
id: best-practices
title: Best Practices
---

## Overview

This guide covers best practices for structuring Cats Effect applications, including application architecture, dependency injection, error handling, testing, and concurrency patterns.

## Application Structure

### Layered Architecture

Organize your application in layers for better maintainability:

```scala mdoc:silent
import cats.effect.IO

// Domain layer - pure business logic
case class User(id: Long, name: String, email: String)
case class UserServiceError(message: String) extends Throwable

// Repository layer - data access
trait UserRepository {
  def findById(id: Long): IO[Option[User]]
  def save(user: User): IO[User]
}

// Service layer - business logic
class UserService(repo: UserRepository) {
  def getUser(id: Long): IO[User] = 
    repo.findById(id).flatMap {
      case Some(user) => IO.pure(user)
      case None => IO.raiseError(UserServiceError(s"User $id not found"))
    }
    
  def createUser(name: String, email: String): IO[User] = {
    val user = User(0, name, email)
    repo.save(user)
  }
}

// Application layer - orchestration
class UserApplication(service: UserService) {
  def processUserRequest(id: Long): IO[String] = 
    service.getUser(id).map(user => s"Processing user: ${user.name}")
}
```

### Dependency Injection

Use constructor injection with Resource for proper lifecycle management:

```scala mdoc:silent
import cats.effect.{IO, Resource}

// Repository implementation
class InMemoryUserRepository extends UserRepository {
  private var users = Map.empty[Long, User]
  
  def findById(id: Long): IO[Option[User]] = 
    IO.pure(users.get(id))
    
  def save(user: User): IO[User] = IO {
    val newId = users.keys.maxOption.getOrElse(0L) + 1
    val savedUser = user.copy(id = newId)
    users = users + (newId -> savedUser)
    savedUser
  }
}

// Application assembly
object ApplicationAssembly {
  def createApplication: Resource[IO, UserApplication] = 
    Resource.pure(new InMemoryUserRepository())
      .map(repo => new UserService(repo))
      .map(service => new UserApplication(service))
}
```

## Error Handling

### Structured Error Types

Define specific error types for better error handling:

```scala mdoc:silent
import cats.effect.IO

// Domain-specific errors
sealed trait AppError extends Throwable
case class ValidationError(message: String) extends AppError
case class NotFoundError(resource: String) extends AppError
case class ExternalServiceError(service: String, cause: Throwable) extends AppError

// Error handling utilities
object ErrorHandling {
  def handleAppError[A](io: IO[A]): IO[Either[AppError, A]] = 
    io.attempt.map {
      case Right(value) => Right(value)
      case Left(error) if error.isInstanceOf[AppError] => Left(error.asInstanceOf[AppError])
      case Left(other) => Left(ExternalServiceError("unknown", other))
    }
    
  def recoverWithDefault[A](io: IO[A], default: A): IO[A] = 
    io.handleErrorWith {
      case _: NotFoundError => IO.pure(default)
      case error => IO.raiseError(error)
    }
}
```

### Error Recovery Patterns

```scala mdoc:silent
import cats.effect.IO
import scala.concurrent.duration._

// Retry with exponential backoff
def retryWithBackoff[A](
  io: IO[A], 
  maxRetries: Int = 3,
  initialDelay: FiniteDuration = 100.millis
): IO[A] = {
  def attempt(retriesLeft: Int, delay: FiniteDuration): IO[A] = 
    io.handleErrorWith { error =>
      if (retriesLeft > 0) {
        IO.sleep(delay) *> attempt(retriesLeft - 1, delay * 2)
      } else {
        IO.raiseError(error)
      }
    }
    
  attempt(maxRetries, initialDelay)
}

// Circuit breaker pattern
class CircuitBreaker(
  failureThreshold: Int = 5,
  timeout: FiniteDuration = 1.minute
) {
  private var failures = 0
  private var lastFailureTime = 0L
  
  def execute[A](io: IO[A]): IO[A] = IO {
    val now = System.currentTimeMillis()
    if (failures >= failureThreshold && (now - lastFailureTime) < timeout.toMillis) {
      throw new RuntimeException("Circuit breaker is open")
    }
  } *> io.handleErrorWith { error =>
    IO {
      failures += 1
      lastFailureTime = System.currentTimeMillis()
    } *> IO.raiseError(error)
  }
}
```

## Resource Management

### Resource Patterns

Always use Resource for managing external resources:

```scala mdoc:silent
import cats.effect.{IO, Resource}
import java.io.{FileInputStream, FileOutputStream}

// File operations with proper resource management
object FileOperations {
  def copyFile(src: String, dest: String): IO[Unit] = 
    Resource.make(IO(new FileInputStream(src)))(fis => IO(fis.close()))
      .flatMap { fis =>
        Resource.make(IO(new FileOutputStream(dest)))(fos => IO(fos.close()))
          .map { fos =>
            val buffer = new Array[Byte](1024)
            var bytesRead = fis.read(buffer)
            while (bytesRead != -1) {
              fos.write(buffer, 0, bytesRead)
              bytesRead = fis.read(buffer)
            }
          }
      }
      .use(IO.pure)
}
```

### Database Connection Management

```scala mdoc:silent
import cats.effect.{IO, Resource}

object DatabaseConfig {
  // Example of resource management pattern for database connections
  def createConnection: Resource[IO, String] = 
    Resource.make(
      IO.println("Connecting to database") *> IO.pure("connection")
    )(_ => IO.println("Closing database connection"))
    
  def queryDatabase(connection: String): IO[String] = 
    IO.println(s"Querying database with $connection") *> IO.pure("result")
}
```

## Testing

### Test Structure

Organize tests with proper setup and teardown:

```scala mdoc:silent
import cats.effect.{IO, Resource}

// Example test structure using Cats Effect testing utilities
class UserServiceSpec {
  
  // Test resource setup
  def testRepository: Resource[IO, UserRepository] = 
    Resource.pure(new InMemoryUserRepository())
  
  def testUserService: IO[String] = {
    testRepository.use { repo =>
      val service = new UserService(repo)
      for {
        user <- service.createUser("John", "john@example.com")
        found <- service.getUser(user.id)
      } yield found.name
    }
  }
}

// Mock service for testing - using the existing UserService class
```

### Property-Based Testing

```scala mdoc:silent
import cats.effect.IO
import cats.syntax.all._

// Example of property-based testing concepts
class UserServicePropertySpec {
  
  def testUserCreation: IO[Boolean] = {
    val testData = List(
      ("Alice", "alice@example.com"),
      ("Bob", "bob@example.com"),
      ("Charlie", "charlie@example.com")
    )
    
    testData.traverse { case (name, email) =>
      val user = User(1L, name, email)
      IO.pure(user.name.nonEmpty && user.email.contains("@"))
    }.map(_.forall(identity))
  }
}
```

## Concurrency Patterns

### Parallel Processing

Use parallel combinators for independent operations:

```scala mdoc:silent
import cats.effect.IO
import cats.syntax.all._

// Parallel execution using start for concurrency
def processUsersParallel(userIds: List[Long]): IO[List[User]] = 
  userIds.traverse { id =>
    // Some expensive operation
    IO.sleep(100.millis).as(User(id, s"User$id", s"user$id@example.com"))
  }

// Parallel with error handling
def processUsersWithErrors(userIds: List[Long]): IO[List[Either[String, User]]] = 
  userIds.traverse { id =>
    if (id % 2 == 0) {
      IO.pure(Right(User(id, s"User$id", s"user$id@example.com")))
    } else {
      IO.pure(Left(s"Error processing user $id"))
    }
  }
```

### Shared State Management

Use Ref for safe shared state:

```scala mdoc:silent
import cats.effect.{IO, Ref}
import cats.syntax.all._

class Counter {
  def create: IO[Ref[IO, Int]] = Ref[IO].of(0)
  
  def increment(counter: Ref[IO, Int]): IO[Int] = 
    counter.updateAndGet(_ + 1)
    
  def getValue(counter: Ref[IO, Int]): IO[Int] = 
    counter.get
    
  def reset(counter: Ref[IO, Int]): IO[Unit] = 
    counter.set(0)
}

// Usage with concurrent access
def concurrentCounterExample: IO[List[Int]] = {
  val counter = new Counter()
  counter.create.flatMap { c =>
    val increments = (1 to 100).toList.map(_ => counter.increment(c))
    increments.sequence
  }
}
```

### Coordination with Deferred

```scala mdoc:silent
import cats.effect.{IO, Deferred}
import cats.syntax.all._

// Producer-consumer with Deferred
def producerConsumerExample: IO[String] = 
  Deferred[IO, String].flatMap { deferred =>
    val producer = IO.sleep(100.millis) *> deferred.complete("Hello from producer!")
    val consumer = deferred.get
    
    (producer, consumer).parTupled.map(_._2)
  }
```

## Configuration Management

### Environment-Based Configuration

```scala mdoc:silent
import cats.effect.IO
import cats.syntax.all._

case class AppConfig(
  databaseUrl: String,
  databaseUser: String,
  databasePassword: String,
  httpPort: Int,
  logLevel: String
)

object ConfigLoader {
  def loadConfig: IO[AppConfig] = 
    (
      IO.fromOption(sys.env.get("DATABASE_URL"))(new RuntimeException("DATABASE_URL not set")),
      IO.fromOption(sys.env.get("DATABASE_USER"))(new RuntimeException("DATABASE_USER not set")),
      IO.fromOption(sys.env.get("DATABASE_PASSWORD"))(new RuntimeException("DATABASE_PASSWORD not set")),
      IO.fromOption(sys.env.get("HTTP_PORT").map(_.toInt))(new RuntimeException("HTTP_PORT not set")),
      IO.pure(sys.env.getOrElse("LOG_LEVEL", "INFO"))
    ).mapN(AppConfig.apply)
}
```

### Configuration Validation

```scala mdoc:silent
import cats.effect.IO

object ConfigValidation {
  def validateConfig(config: AppConfig): IO[AppConfig] = 
    IO.raiseWhen(config.httpPort < 1 || config.httpPort > 65535)(
      new IllegalArgumentException("HTTP_PORT must be between 1 and 65535")
    ) *> IO.raiseWhen(config.databaseUrl.isEmpty)(
      new IllegalArgumentException("DATABASE_URL cannot be empty")
    ) *> IO.pure(config)
}
```

## Logging

### Structured Logging

```scala mdoc:silent
import cats.effect.IO
import cats.syntax.all._

// Simple logging example using IO.println
object LoggingExample {
  
  def logUserAction(userId: Long, action: String): IO[Unit] = 
    IO.println(s"[INFO] User $userId performed action: $action")
    
  def logError(error: Throwable): IO[Unit] = 
    IO.println(s"[ERROR] An error occurred: ${error.getMessage}")
    
  def logWithContext(userId: Long, action: String): IO[Unit] = 
    IO.println(s"[INFO] User action - userId: $userId, action: $action")
}
```

## Performance Considerations

### Avoid Blocking Operations

```scala mdoc:silent
import cats.effect.IO

// ❌ Don't block the compute pool
val badExample: IO[String] = IO {
  Thread.sleep(1000)  // Blocks compute pool!
  "done"
}

// ✅ Use IO.blocking for blocking operations
val goodExample: IO[String] = IO.blocking {
  Thread.sleep(1000)  // Runs on blocking pool
  "done"
}
```

### Batch Operations

```scala mdoc:silent
// ❌ Many small operations
val inefficient: IO[List[String]] = 
  (1 to 1000).toList.traverse { i =>
    IO.pure(s"item-$i")
  }

// ✅ Batch operations when possible
val efficient: IO[List[String]] = IO.pure {
  (1 to 1000).toList.map(i => s"item-$i")
}
```

## Monitoring and Observability

### Metrics Collection

```scala mdoc:silent
import cats.effect.IO
import cats.syntax.all._

// Simple metrics example
object MetricsExample {
  def createMetrics: IO[(Ref[IO, Int], Ref[IO, Int])] = 
    (Ref[IO].of(0), Ref[IO].of(0)).tupled
    
  def incrementRequestCount(requestCount: Ref[IO, Int]): IO[Unit] = 
    requestCount.update(_ + 1)
    
  def incrementErrorCount(errorCount: Ref[IO, Int]): IO[Unit] = 
    errorCount.update(_ + 1)
    
  def getMetrics(requestCount: Ref[IO, Int], errorCount: Ref[IO, Int]): IO[Map[String, Int]] = 
    (requestCount.get, errorCount.get).mapN { (req, err) =>
      Map("requests" -> req, "errors" -> err)
    }
}
```

### Health Checks

```scala mdoc:silent
import cats.effect.IO
import cats.syntax.all._

trait HealthCheck {
  def check: IO[Boolean]
}

class DatabaseHealthCheck(connection: String) extends HealthCheck {
  def check: IO[Boolean] = 
    IO.println(s"Checking database connection: $connection")
      .as(true)
      .handleError(_ => false)
}

class ApplicationHealthCheck(checks: List[HealthCheck]) {
  def overallHealth: IO[Map[String, Boolean]] = 
    checks.zipWithIndex.traverse { case (check, index) =>
      check.check.map(s"check-$index" -> _)
    }.map(_.toMap)
}
```

## Summary

These best practices will help you build robust, maintainable Cats Effect applications:

1. **Structure** your application in layers
2. **Inject** dependencies using constructor injection
3. **Handle** errors with specific error types
4. **Manage** resources with Resource
5. **Test** thoroughly with proper setup/teardown
6. **Use** parallel combinators for independent operations
7. **Configure** your application from environment variables
8. **Log** structured information for debugging
9. **Monitor** your application with metrics and health checks
10. **Avoid** blocking operations on the compute pool

For more advanced patterns and production considerations, see the [Scaling and Tuning](scaling-tuning.md) guide.
