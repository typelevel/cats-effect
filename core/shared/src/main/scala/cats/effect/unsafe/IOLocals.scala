package cats.effect
package unsafe

// TODO handle defaults and lenses. all do-able, just needs refactoring ...
object IOLocals {

  def get[A](iol: IOLocal[A]): A = {
    val thread = Thread.currentThread()
    val state =
      if (thread.isInstanceOf[WorkerThread])
        thread.asInstanceOf[WorkerThread].ioLocalState
      else
        threadLocal.get
    state(iol).asInstanceOf[A]
  }

  def set[A](iol: IOLocal[A], value: A): Unit = {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread])
      thread.asInstanceOf[WorkerThread].ioLocalState += (iol -> value)
    else
      threadLocal.set(threadLocal.get() + (iol -> value))
  }

  def reset[A](iol: IOLocal[A]): Unit = {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread])
      thread.asInstanceOf[WorkerThread].ioLocalState -= iol
    else
      threadLocal.set(threadLocal.get() - iol)
  }

  // TODO other ops from IOLocal

  private[this] val threadLocal = new ThreadLocal[IOLocalState] {
    override def initialValue() = IOLocalState.empty
  }

  private[effect] def getState = {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread])
      thread.asInstanceOf[WorkerThread].ioLocalState
    else
      threadLocal.get()
  }

  private[effect] def setState(state: IOLocalState) = {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread])
      thread.asInstanceOf[WorkerThread].ioLocalState = state
    else
      threadLocal.set(state)
  }

  private[effect] def getAndClearState() = {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread]) {
      val worker = thread.asInstanceOf[WorkerThread]
      val state = worker.ioLocalState
      worker.ioLocalState = IOLocalState.empty
      state
    } else {
      val state = threadLocal.get()
      threadLocal.set(IOLocalState.empty)
      state
    }
  }
}
