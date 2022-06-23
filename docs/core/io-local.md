---
id: io-local
title: IOLocal
---

`IOLocal` provides a handy way of sharing a context within the fiber runtime.  
`IO` stores local states as `scala.collection.immutable.Map[IOLocal[_], Any]` under the hood.  
That means, two fibers can never access the same `IOLocal`, they will always be working on their own copies.  
In some scenarios, `IOLocal` can be considered as an alternative to `cats.data.Reader` or `cats.data.Kleisli`.  

See the following example, of how the `IOLocal` can be used for the propagation of a `TraceId`:

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
