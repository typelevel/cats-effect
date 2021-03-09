package cats.effect.std

import cats.effect.kernel.Sync

import java.util.UUID

trait UUIDGen[F[_]] {
  def randomUUID: F[UUID]
  def fromString(uuidString: String)
}

object UUIDGen {
  def randomUUID[F[_]: Sync]: F[UUID] = Sync[F].delay(UUID.randomUUID())
  def fromString[F[_]: Sync](uuidString: String): F[UUID] = Sync[F].delay(UUID.fromString(uuidString))
}

