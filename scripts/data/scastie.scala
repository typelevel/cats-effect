import cats.effect.{IO, IOApp}
import cats.effect.std.Random

import scala.concurrent.duration._

object Hello extends IOApp.Simple {

  def sleepPrint(word: String, name: String, rand: Random[IO]) =
    for {
      delay <- rand.betweenInt(200, 700)
      _ <- IO.sleep(delay.millis)
      _ <- IO.println(s"$word, $name")
    } yield ()

  val run =
    for {
      rand <- Random.scalaUtilRandom[IO]

      // try uncommenting first one locally! Scastie doesn't like System.in
      // name <- IO.print("Enter your name: ") >> IO.readLine
      name <- IO.pure("Daniel")

      english <- sleepPrint("Hello", name, rand).foreverM.start
      french <- sleepPrint("Bonjour", name, rand).foreverM.start
      spanish <- sleepPrint("Hola", name, rand).foreverM.start

      _ <- IO.sleep(5.seconds)
      _ <- english.cancel >> french.cancel >> spanish.cancel
    } yield ()
}
