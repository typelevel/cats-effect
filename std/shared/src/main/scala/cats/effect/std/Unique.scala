package cats
package effect
package std

import cats.Hash
import cats.effect.Sync
import cats.syntax.all._

final class Unique private extends Serializable {
  override def toString: String = s"Unique(${hashCode.toHexString})"
}
object Unique {
  def apply[F[_]: Concurrent]: F[Unique] = Concurrent[F].unit.as(new Unique)

  implicit val uniqueInstances : Hash[Unique] =
    Hash.fromUniversalHashCode[Unique]
}