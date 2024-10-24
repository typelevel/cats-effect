---
id: io-local
title: IOLocal
---

`IOLocal` provides a handy way of manipulating a context on different scopes.
In some scenarios, `IOLocal` can be considered as an alternative to `cats.data.Kleisli`.

`IOLocal` should not be treated as `Ref`, since the former abides different laws.

Once a fiber is forked, for example by `Spawn[F].start`, the forked fiber manipulates the 
copy of the parent's context. For example, two forked fibers will never see each other's
modifications to the same `IOLocal`, each fiber will only see its own modifications.

### Operations on `IOLocal` are visible to the fiber 

```
┌────────────┐               ┌────────────┐               ┌────────────┐
│  Fiber A   │ update(_ + 1) │  Fiber A   │ update(_ + 1) │  Fiber A   │
│ (local 42) │──────────────►│ (local 43) │──────────────►│ (local 44) │
└────────────┘               └────────────┘               └────────────┘
```

```scala mdoc:silent
import cats.effect.{IO, IOLocal}
import scala.concurrent.duration._

def inc(idx: Int, local: IOLocal[Int]): IO[Unit] =
  local.update(_ + 1) >> local.get.flatMap(current => IO.println(s"update $idx: $current"))

for {
  local   <- IOLocal(42)
  _       <- inc(1, local)
  _       <- inc(2, local)
  current <- local.get
  _       <- IO.println(s"fiber A: $current")
} yield ()

// output:
// update 1: 43
// update 2: 44
// fiber A: 44
```

---

### A forked fiber operates on a copy of the parent `IOLocal`

A **forked** fiber (i.e. via `Spawn[F].start`) operates on a **copy** of the parent `IOLocal`.
Hence, the children operations are not reflected on the parent context.

```
                      ┌────────────┐               ┌────────────┐
               fork   │  Fiber B   │ update(_ - 1) │  Fiber B   │
               ┌─────►│ (local 42) │──────────────►│ (local 41) │
               │      └────────────┘               └────────────┘
┌────────────┐─┘                                   ┌────────────┐
│  Fiber A   │                                     │  Fiber A   │
│ (local 42) │────────────────────────────────────►│ (local 42) │
└────────────┘─┐                                   └────────────┘
               │      ┌────────────┐               ┌────────────┐
               │ fork │  Fiber C   │ update(_ + 1) │  Fiber C   │
               └─────►│ (local 42) │──────────────►│ (local 43) │
                      └────────────┘               └────────────┘
```

```scala mdoc:nest:silent
def update(name: String, local: IOLocal[Int], f: Int => Int): IO[Unit] =
  local.update(f) >> local.get.flatMap(current => IO.println(s"$name: $current"))
    
for {
  local   <- IOLocal(42)
  fiberA  <- update("fiber B", local, _ - 1).start
  fiberB  <- update("fiber C", local, _ + 1).start
  _       <- fiberA.joinWithNever
  _       <- fiberB.joinWithNever
  current <- local.get
  _       <- IO.println(s"fiber A: $current")
} yield ()

// output:
// fiber B: 41
// fiber C: 43
// fiber A: 42
```

---

### Parent operations on `IOLocal` are invisible to children

```
                      ┌────────────┐               ┌────────────┐
                 fork │  Fiber B   │ update(_ + 1) │  Fiber B   │
               ┌─────►│ (local 42) │──────────────►│ (local 43) │
               │      └────────────┘               └────────────┘
┌────────────┐─┘                                   ┌────────────┐
│  Fiber A   │        update(_ - 1)                │  Fiber A   │
│ (local 42) │────────────────────────────────────►│ (local 41) │
└────────────┘─┐                                   └────────────┘
               │      ┌────────────┐               ┌────────────┐
               │ fork │  Fiber C   │ update(_ + 2) │  Fiber C   │
               └─────►│ (local 42) │──────────────►│ (local 44) │
                      └────────────┘               └────────────┘
```

```scala mdoc:nest:passthrough
def update(name: String, local: IOLocal[Int], f: Int => Int): IO[Unit] =
  IO.sleep(1.second) >> local.update(f) >> local.get.flatMap(current => IO.println(s"$name: $current"))
  
for {
  local  <- IOLocal(42)
  fiber1 <- update("fiber B", local, _ + 1).start
  fiber2 <- update("fiber C", local, _ + 2).start
  _      <- fiber1.joinWithNever
  _      <- fiber2.joinWithNever
  _      <- update("fiber A", local, _ - 1)
} yield ()

// output:
// fiber B: 43
// fiber C: 44
// fiber A: 41
```

---

### Example: TraceId propagation 

The `IOLocal` can be used for the propagation of a `TraceId`:

```scala mdoc:silent
import cats.Monad
import cats.effect.{IO, IOLocal, Sync, Resource}
import cats.effect.std.{Console, Random}
import cats.syntax.flatMap._
import cats.syntax.functor._

case class TraceId(value: String)

object TraceId {
  def gen[F[_]: Sync]: F[TraceId] =
    Random.scalaUtilRandom[F].flatMap(_.nextString(8)).map(TraceId(_))
}

trait TraceIdScope[F[_]] {
  def get: F[TraceId]
  def scope(traceId: TraceId): Resource[F, Unit]
}

object TraceIdScope {
  
  def apply[F[_]](implicit ev: TraceIdScope[F]): TraceIdScope[F] = ev
  
  def fromIOLocal: IO[TraceIdScope[IO]] =
    for {
      local <- IOLocal(TraceId("global"))
    } yield new TraceIdScope[IO] {
      def get: IO[TraceId] =
        local.get

      def scope(traceId: TraceId): Resource[IO, Unit] =
        Resource.make(local.getAndSet(traceId))(previous => local.set(previous)).void
    }
    
}

def service[F[_]: Sync: Console: TraceIdScope]: F[String] =
  for {
    traceId <- TraceId.gen[F]
    result <- TraceIdScope[F].scope(traceId).use(_ => callRemote[F])
  } yield result
  
def callRemote[F[_]: Monad: Console: TraceIdScope]: F[String] =
  for {
    traceId <- TraceIdScope[F].get
    _ <- Console[F].println(s"Processing request. TraceId: ${traceId}")
  } yield "some response"
  
TraceIdScope.fromIOLocal.flatMap { implicit traceIdScope: TraceIdScope[IO] =>
  service[IO]
}
```

## Propagating `IOLocal`s as `ThreadLocal`s

To support integration with Java libraries, `IOLocal` interoperates with the JDK `ThreadLocal` API via `IOLocal#unsafeThreadLocal`. This makes it possible to unsafely read and write the value of an `IOLocal` on the currently running fiber within a suspended side-effect (e.g. `IO.delay` or `IO.blocking`).

To use this feature you must set the property `cats.effect.ioLocalPropagation=true`. Note that enabling propagation causes a performance hit of up to 25% in some of our microbenchmarks. However, it is not clear that this performance impact matters in practice.
