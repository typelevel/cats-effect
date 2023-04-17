package cats.effect.std

import cats.effect.kernel.Resource

abstract class Lock[F[_]] {
  def shared: Resource[F, Unit]
  def exclusive: Resource[F, Unit]
}
