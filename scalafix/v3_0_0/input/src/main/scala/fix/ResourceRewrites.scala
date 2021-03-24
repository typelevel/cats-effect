/*
rule = "scala:fix.v3_0_0"
 */
package fix

import cats.syntax.all._
import cats.Parallel
import cats.effect.IO
import cats.effect.Resource

object ResourceRewrites {
  Resource.liftF(IO.unit)

  def f1(implicit p: Parallel[IO]): Resource[IO, Unit] =
    Resource.liftF(IO.unit).parZip(Resource.liftF(IO.unit)).void
}
