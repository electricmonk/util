package com.twitter.concurrent

/**
 * An AsyncSemaphore is a traditional semaphore but with asynchronous
 * execution. Grabbing a permit returns a Future[Permit]
 */

import java.util.concurrent.RejectedExecutionException
import java.util.ArrayDeque
import com.twitter.util.{Promise, Future}

class AsyncSemaphore protected (initialPermits: Int, maxWaiters: Option[Int]) {
  def this(initialPermits: Int = 0) = this(initialPermits, None)
  def this(initialPermits: Int, maxWaiters: Int) = this(initialPermits, Some(maxWaiters))
  require(maxWaiters.getOrElse(0) >= 0)
  private[this] val waitq = new ArrayDeque[() => Unit]
  @volatile private[this] var availablePermits = initialPermits

  private[this] class SemaphorePermit extends Permit {
    /**
     * Indicate that you are done with your Permit.
     */
    def release() = {
      val run = AsyncSemaphore.this.synchronized {
        availablePermits += 1
        if (availablePermits > 0 && !waitq.isEmpty) {
          availablePermits -= 1
          Some(waitq.removeFirst())
        } else {
          None
        }
      }

      run foreach { _() }
    }
  }

  def numWaiters = synchronized(waitq.size)
  def numPermitsAvailable = availablePermits

  /**
   * Acquire a Permit, asynchronously. Be sure to permit.release() in a 'finally'
   * block of your onSuccess() callback.
   *
   * @return a Future[Permit] when the Future is satisfied, computation can proceed,
   * or a Future.Exception[RejectedExecutionException] if the configured maximum number of waitq
   * would be exceeded.
   */
  def acquire(): Future[Permit] = {
    val result = new Promise[Permit]

    def setAcquired() {
      result.setValue(new SemaphorePermit)
    }

    val (isException, runNow) = synchronized {
      if (availablePermits > 0) {
        availablePermits -= 1
        (false, true)
      } else {
        maxWaiters match {
          case Some(max) if (waitq.size >= max) =>
            (true, false)
          case _ =>
            waitq.addLast(setAcquired)
            (false, false)
        }
      }
    }

    if (isException) {
      AsyncSemaphore.MaxWaitersExceededException
    } else {
      if (runNow) setAcquired()
      result
    }
  }
}

object AsyncSemaphore {
  private val MaxWaitersExceededException =
    Future.exception(new RejectedExecutionException("Max waiters exceeded"))
}
