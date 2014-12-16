package scala.slick.action

import org.reactivestreams.{Subscription, Subscriber}

import scala.slick.backend.DatabaseComponent
import scala.slick.util.{CloseableIterator, ignoreFollowOnError}

/** Some utility methods for working with database results in a synchronous or blocking way that
  * can be detrimental to performance when used incorrectly. */
object Unsafe {
  /** Run an Action and block the current thread until the result is ready. If the Database uses
    * synchronous, blocking excution, it is performed on the current thread in order to avoid any
    * context switching, otherwise execution happens asynchronously. */
  def runBlocking[R](db: DatabaseComponent#DatabaseDef, a: Action[Nothing, R, NoStream]): R =
    db.runInternal(a.asInstanceOf[Action[Effect, R, NoStream]], true).value.get.get

  /** Run a streaming Action and return an `Iterator` which performs blocking I/O on the current
    * thread (if supported by the Database) or blocks the current thread while waiting for the
    * next result. */
  def blockingIterator[S](db: DatabaseComponent#DatabaseDef, a: Action[Nothing, Any, Streaming[S]]): CloseableIterator[S] = new CloseableIterator[S] {
    val p = db.streamInternal(a.asInstanceOf[Action[Effect, Any, Streaming[S]]], true)
    var error: Throwable = null
    var sub: Subscription = null
    var value: AnyRef = null
    var cached: Boolean = false
    var complete: Boolean = false
    p.subscribe(new Subscriber[S] {
      def onSubscribe(s: Subscription): Unit = {
        sub = s
        s.request(1L)
      }
      def onError(t: Throwable): Unit = error = t
      def onComplete(): Unit = complete = true
      def onNext(t: S): Unit = {
        if(cached) throw new IllegalStateException("Unexpected duplicate onNext()") else {
          cached = true
          value = t.asInstanceOf[AnyRef]
        }
      }
    })

    def hasNext: Boolean = if(error ne null) throw error else {
      buffer()
      cached
    }

    def next(): S = if(error ne null) throw error else {
      buffer()
      if(cached) {
        cached = false
        value.asInstanceOf[S]
      } else throw new IllegalStateException("No more results")
    }

    def buffer(): Unit = {
      if(!complete && !cached) {
        sub.request(1L)
        if(!complete && !cached) {
          try close() catch ignoreFollowOnError
          throw new IllegalStateException("Unepected missing result after request()")
        }
      }
    }

    def close(): Unit = if(!complete) sub.cancel()
  }
}
