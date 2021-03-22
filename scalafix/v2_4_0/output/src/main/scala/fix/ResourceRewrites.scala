package fix

import cats.effect.IO
import cats.effect.Resource

object ResourceRewrites {
  Resource.eval(IO.unit)
  cats.effect.Resource.eval(IO.unit)
}
