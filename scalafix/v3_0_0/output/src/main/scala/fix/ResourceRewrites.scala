package fix

import cats.syntax.all._
import cats.Parallel
import cats.effect.IO
import cats.effect.Resource

object ResourceRewrites {
  Resource.eval(IO.unit)

  def f1(implicit p: Parallel[IO]): Resource[IO, Unit] =
    Resource.eval(IO.unit).both(Resource.eval(IO.unit)).void
}
