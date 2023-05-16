package cats.effect
package unsafe

// TODO handle defaults and lenses. all do-able, just needs refactoring ...
object IOLocals {
  def get[A](iol: IOLocal[A]): A = threadLocal.get.apply(iol).asInstanceOf[A]

  def set[A](iol: IOLocal[A], value: A): Unit =
    threadLocal.set(threadLocal.get() + (iol -> value))

  def reset[A](iol: IOLocal[A]): Unit = threadLocal.set(threadLocal.get() - iol)

  // TODO other ops from IOLocal

  private[this] val empty: IOLocalState = Map.empty
  private[this] val threadLocal = new ThreadLocal[IOLocalState] {
    override def initialValue() = empty
  }

  private[effect] def setState(state: IOLocalState) = threadLocal.set(state)
  private[effect] def getAndClearState() = {
    val state = threadLocal.get()
    threadLocal.set(empty)
    state
  }
}
