package cats.effect.benchmarks

import java.util.concurrent._
import cats.effect.{ExitCode, IO, IOApp, Resource, SyncIO}
import cats.implicits._
import org.openjdk.jmh.annotations._
import scala.concurrent.ExecutionContext

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class ECBenchmark {
  trait Run { self: IOApp =>
    val size = 100000
    def run(args: List[String]) = {
      def loop(i: Int): IO[Int] =
        if (i < size) IO.shift.flatMap(_ => IO.pure(i + 1)).flatMap(loop)
        else IO.shift.flatMap(_ => IO.pure(i))

      IO(0).flatMap(loop).as(ExitCode.Success)
    }
  }

  private val ioApp = new IOApp with Run
  private val ioAppCtx = new IOApp.WithContext with Run {
    protected def executionContextResource: Resource[SyncIO, ExecutionContext] =
      Resource.liftF(SyncIO.pure(ExecutionContext.Implicits.global))
  }

  @Benchmark
  def app(): Unit = {
    val _ = ioApp.main(Array.empty)
  }

  @Benchmark
  def appWithCtx(): Unit = {
    val _ = ioAppCtx.main(Array.empty)
  }
}
