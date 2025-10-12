---
id: performance
title: Performance
---

## Overview

Cats Effect is designed for high-performance, production-ready applications. This page covers the key performance characteristics, benchmarks, and tuning guidance for Cats Effect applications.

## Fiber Performance

### Lightweight Fibers

Cats Effect fibers are extremely lightweight, requiring only ~150 bytes per fiber. This enables:

- **Millions of concurrent fibers** in a single process
- **Fast fiber creation** (~1-2 microseconds per fiber)
- **Efficient context switching** with work-stealing scheduler

```scala mdoc:silent
import cats.effect.IO
import cats.syntax.all._
import scala.concurrent.duration._

// Creating millions of fibers is feasible
val manyFibers: IO[List[Unit]] = 
  (1 to 1000000).toList.traverse { _ =>
    IO.sleep(1.millis).as(())
  }
```

### Work-Stealing Scheduler

The work-stealing scheduler provides:

- **Automatic load balancing** across CPU cores
- **Low contention** with per-thread work queues
- **Efficient task migration** when threads become idle

## Memory Usage

### Heap Efficiency

Cats Effect applications typically show:

- **Low memory overhead** per concurrent operation
- **Predictable memory growth** patterns
- **Efficient garbage collection** characteristics

### Memory Tuning

For high-throughput applications:

```scala mdoc:silent
import cats.effect.unsafe.IORuntimeConfig

// Optimize for throughput
val config = IORuntimeConfig()
```

## Throughput Benchmarks

### Typical Performance Numbers

Based on community benchmarks and production deployments:

- **Fiber creation**: ~1-2 μs per fiber
- **Context switching**: ~100-200 ns per switch
- **IO operations**: Comparable to Future performance
- **Memory per fiber**: ~150 bytes

### Concurrent Operations

```scala mdoc:silent
import cats.effect.IO
import cats.syntax.all._

// Parallel execution scales well
val parallelWork: IO[List[Int]] = 
  (1 to 1000).toList.traverse { i =>
    IO.pure(i * 2)
  }
```

## Performance Tuning

### Runtime Configuration

Key parameters for performance tuning:

```scala mdoc:silent
import cats.effect.unsafe.{IORuntime, IORuntimeConfig}
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

// Custom runtime for high-performance scenarios
val highPerfRuntime = IORuntime(
  compute = ExecutionContext.fromExecutor(
    Executors.newWorkStealingPool(Runtime.getRuntime.availableProcessors())
  ),
  blocking = ExecutionContext.fromExecutor(
    Executors.newCachedThreadPool()
  ),
  scheduler = cats.effect.unsafe.Scheduler.fromScheduledExecutor(
    Executors.newScheduledThreadPool(2)
  ),
  shutdown = () => (),
  config = IORuntimeConfig()
)
```

### Auto-Yield Threshold

Controls how often fibers yield control:

- **Lower values** (256-512): Better responsiveness, more context switching
- **Higher values** (1024-2048): Better throughput, less context switching

### Blocking Operations

Use `IO.blocking` for CPU-intensive or blocking operations:

```scala mdoc:silent
import cats.effect.IO

// CPU-intensive work
val cpuIntensive: IO[Int] = IO.blocking {
  (1 to 1000000).sum
}

// Blocking I/O
val blockingIO: IO[String] = IO.blocking {
  // Some blocking operation
  "result"
}
```

## Profiling and Monitoring

### Fiber Dumps

Enable fiber dumps for debugging performance issues:

```scala mdoc:silent
import cats.effect.unsafe.IORuntimeConfig

val configWithDumps = IORuntimeConfig()
```

### Memory Profiling

Use standard JVM profiling tools:

- **JProfiler** or **VisualVM** for heap analysis
- **Async Profiler** for CPU profiling
- **JFR** (Java Flight Recorder) for detailed runtime analysis

## Best Practices

### Avoid Blocking the Compute Pool

```scala mdoc:silent
// ❌ Don't block the compute pool
val badExample: IO[String] = IO {
  Thread.sleep(1000)  // Blocks the compute pool!
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
import cats.effect.IO
import cats.syntax.all._

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

### Resource Management

```scala mdoc:silent
import cats.effect.Resource

// ✅ Proper resource management
val resourceExample: Resource[IO, String] = 
  Resource.make(
    IO.println("Acquiring resource") *> IO.pure("resource")
  )(_ => IO.println("Releasing resource"))
```

## Production Considerations

### JVM Tuning

For production deployments:

```bash
# JVM flags for Cats Effect applications
-Xmx4g -Xms4g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:+UnlockExperimentalVMOptions
-XX:+UseJVMCICompiler
```

### Monitoring

Key metrics to monitor:

- **Fiber count**: Should be reasonable for your workload
- **Memory usage**: Watch for memory leaks
- **GC pauses**: Should be minimal with proper tuning
- **Thread utilization**: Compute pool should be well-utilized

## Comparison with Alternatives

### vs Future

- **Similar performance** for basic operations
- **Better resource management** with Resource
- **Superior cancellation** support
- **More predictable** memory usage

### vs Akka

- **Lower memory overhead** per concurrent operation
- **Simpler mental model** (no actor system complexity)
- **Better composability** with functional programming
- **More efficient** for I/O bound workloads

For more advanced scaling and tuning guidance, see the [Scaling and Tuning](scaling-tuning.md) guide.
