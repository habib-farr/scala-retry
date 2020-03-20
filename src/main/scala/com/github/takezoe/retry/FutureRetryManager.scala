package com.github.takezoe.retry

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class RetryManager {

  private val tasks = new ConcurrentLinkedQueue[FutureRetryTask]()

  private val running = new AtomicBoolean(true)

  private val thread = new Thread {
    override def run(): Unit = {
      while(running.get()){
        val currentTime = System.currentTimeMillis
        tasks.iterator().forEachRemaining { task =>
          if(task.nextRun <= currentTime){
            tasks.remove(task)
            val future = task.f()
            future.onComplete {
              case Success(v) => task.promise.success(v)
              case Failure(e) => {
                if(task.count == task.policy.maxAttempts){
                  task.policy.onFailure(e)
                  task.promise.failure(e)
                } else {
                  task.policy.onRetry(e)
                  val count = task.count + 1
                  val nextDuration = task.policy.backOff.nextDuration(count, task.policy.retryDuration.toMillis)
                  val nextRun = currentTime + nextDuration + jitter(task.policy.jitter.toMillis)
                  tasks.add(new FutureRetryTask(task.f, task.policy, task.ec, task.promise, nextRun, count))
                }
              }
            }(task.ec)
          }
        }
      }
      Thread.sleep(100)
    }
  }

  thread.start()

  def scheduleFuture[T](f: => Future[T])(implicit policy: RetryPolicy, ec: ExecutionContext): Future[T] = {
    val promise = Promise[T]()
    val task = new FutureRetryTask(() => f, policy, ec, promise.asInstanceOf[Promise[Any]], System.currentTimeMillis + policy.retryDuration.toMillis)
    tasks.add(task)
    promise.future
  }

  def shutdown(): Unit = {
    running.set(false)
  }

}

private[retry] class FutureRetryTask(
  val f: () => Future[Any],
  val policy: RetryPolicy,
  val ec: ExecutionContext,
  val promise: Promise[Any],
  val nextRun: Long,
  val count: Int = 1
)