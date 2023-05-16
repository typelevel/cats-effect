package cats.effect
package unsafe

// TODO handle defaults and lenses. all do-able, just needs refactoring ...
final class IOLocals private[effect] (private[this] var state: IOLocalState) {

  private[effect] def getState(): IOLocalState = state

  def get[A](iol: IOLocal[A]): A = state(iol).asInstanceOf[A]

  def set[A](iol: IOLocal[A], value: A): Unit = state += (iol -> value)

  def reset[A](iol: IOLocal[A]): Unit = state -= iol

  // TODO other ops from IOLocal

}

object IOLocals {
  val threadLocal = ThreadLocal.withInitial[IOLocals](() => null)
}
