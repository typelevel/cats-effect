/*
rule = "scala:fix.v3_0_0"
 */
package fix

import cats.effect.IO
import cats.effect.Sync
import cats.effect.Bracket
import cats.effect.BracketThrow

object BracketRewrites {
  Bracket.apply[IO, Throwable]

  Bracket[IO, Throwable].guarantee(IO.unit)(IO.unit)

  Sync[IO].guarantee(IO.unit)(IO.unit)

  Sync[IO].guarantee( /* comment */ IO.unit)(IO.unit)

  Sync[IO].guarantee(Sync[IO].guarantee(IO.unit)(IO.unit))(IO.unit)

  Sync[IO].guarantee(IO.suspend(Sync[IO].guarantee(IO.unit)(IO.unit)))(IO.unit)

  def f1[F[_], E](implicit F: Bracket[F, E]): Unit = ()

  private val x1 = Bracket[IO, Throwable]
  x1.guarantee(IO.unit)(IO.unit)

  trait MySync[F[_]] extends Bracket[F, Throwable]

  Bracket[IO, Throwable].uncancelable(IO.unit)

  Bracket[IO, Throwable].uncancelable(Sync[IO].guarantee(IO.unit)(IO.unit))

  def f2(ev: BracketThrow[IO]): Unit = ()
}
