package fix

import cats.effect.IO
import cats.effect.Resource

object ResourceRewrites {
  Resource.eval(IO.unit)

  // TODO: Resource#parZip -> Resource#both when 3.0.0-M6 is released
}
