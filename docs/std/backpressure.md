---
id: backpressure
title: Backpressure
---

`Backpressure` allows running effects through the rate-limiting strategy.
`Backpressure` can be utilized in a scenario where a large number of tasks need to be processed, but only a certain number of resources are available.
In other words, backpressure is needed when there is not enough time or resources available for processing all the requests on your system.

`Backpressure` has two strategies that work as follows:
- `Lossy` - an effect **will not be** run in the presence of backpressure.
- `Lossless` - an effect **will run** in the presence of backpressure. However, the effect will semantically block until backpressure is alleviated.

```scala
trait Backpressure[F[_]] {
  def metered[A](f: F[A]): F[Option[A]]
}
```

## Using `Backpressure`

```scala mdoc:silent
import cats.effect.IO
import cats.effect.std.Backpressure
import scala.concurrent.duration._

val program = 
  for {
    backpressure <- Backpressure[IO](Backpressure.Strategy.Lossless, 1)
    f1 <- backpressure.metered(IO.sleep(1.second) *> IO.pure(1)).start
    f2 <- backpressure.metered(IO.sleep(1.second) *> IO.pure(1)).start
    res1 <- f1.joinWithNever
    res2 <- f2.joinWithNever
  } yield (res1, res2)
```
