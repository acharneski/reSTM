/*
 * Copyright (c) 2017 by Andrew Charneski.
 *
 * The author licenses this file to you under the
 * Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package stm.task

import java.util.Date
import java.util.concurrent.{Executors, LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}

import _root_.util.Util._
import com.google.common.util.concurrent.ThreadFactoryBuilder
import stm._
import stm.task.Task._
import storage.Restm

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}


object StmExecutionQueue {
  private var default: StmExecutionQueue = _
  //private implicit def executionContext = StmDaemons.executionContext

  def init()(implicit cluster: Restm): Future[StmExecutionQueue] = {
    new STMTxn[StmExecutionQueue] {
      override def txnLogic()(implicit ctx: STMTxnCtx): Future[StmExecutionQueue] = {
        TaskQueue.init[Task[_]](5, "StmExecutionQueue").map(new StmExecutionQueue(_))(StmDaemons.executionContext)
      }
    }.txnRun(cluster).map(x => {
      default = x;
      x
    })(StmDaemons.executionContext)
  }

  def get(): StmExecutionQueue = {
    default
  }

  def reset()(implicit cluster: Restm): Unit = {
    default = null
  }

  private[StmExecutionQueue] lazy val pool = new ThreadPoolExecutor(2, 16, 5L, TimeUnit.SECONDS,
    new LinkedBlockingQueue[Runnable], //new SynchronousQueue[Runnable],
    new ThreadFactoryBuilder().setNameFormat("worker-pool-%d").build())
  private[StmExecutionQueue] lazy val taskpool = Executors.newCachedThreadPool(
    new ThreadFactoryBuilder().setNameFormat("task-pool-%d").build())
  private[StmExecutionQueue] lazy val executionContext: ExecutionContext = ExecutionContext.fromExecutor(pool)
  private[StmExecutionQueue] lazy val taskExecutionContext: ExecutionContext = ExecutionContext.fromExecutor(taskpool)
}

class StmExecutionQueue(val workQueue: TaskQueue[Task[_]]) {

  var verbose = false

  def atomic(implicit cluster: Restm) = new AtomicApi

  def sync(duration: Duration) = new SyncApi(duration)

  def sync = new SyncApi(10.seconds)

  def add[T](f: (Restm, ExecutionContext) => TaskResult[T], ancestors: List[Task[_]] = List.empty)(implicit ctx: STMTxnCtx): Future[Task[T]] = {
    implicit def executionContext = StmPool.executionContext
    Task.create(f, ancestors).flatMap(task => task.initTriggers(StmExecutionQueue.this).map(_ => task))
  }

  def add(f: Task[_])(implicit ctx: STMTxnCtx): Future[Unit] = {
    workQueue.add(f)
  }

  def registerDaemons(count: Int = 1)(implicit cluster: Restm): Unit = {
    (1 to count).map(i => {
      val f: (Restm, ExecutionContext) => Unit = task()
      DaemonConfig(s"Queue-${workQueue.id}-$i", f)
    }).foreach(daemon => StmDaemons.config.atomic().sync.add(daemon))
  }

  def task()(cluster: Restm, executionContext: ExecutionContext): Unit = {
    implicit val _executionContext = executionContext
    try {
      var lastExecuted = now
      while (!Thread.interrupted()) {
        try {
          val future = runTask(cluster)
          val result = Await.result(future, 10.minutes)
          if (!result) {
            Thread.sleep(Math.max(Math.min((now - lastExecuted).toMillis * 2, 500), 1))
          } else {
            lastExecuted = now
          }
        } catch {
          case _: InterruptedException =>
            Thread.currentThread().interrupt()
          case e: Throwable =>
            e.printStackTrace()
        }
      }
    } catch {
      case e: Throwable =>
        new RuntimeException(s"Error in task executor", e).printStackTrace(System.err)
    }
  }

  private[this] def runTask(implicit cluster: Restm): Future[Boolean] = {
    val executorId = ExecutionStatusManager.getName
    val taskFuture = monitorFuture("StmExecutionQueue.getTask") {
      new STMTxn[Option[(Task[_], (Restm, ExecutionContext) => TaskResult[_])]] {
        override def txnLogic()(implicit ctx: STMTxnCtx): Future[Option[(Task[AnyRef], (Restm, ExecutionContext) => TaskResult[AnyRef])]] = {
          getTask(executorId)
        }
      }.txnRun(cluster)
    }(StmDaemons.executionContext)

    taskFuture.flatMap(taskTuple => {
      taskTuple.map(tuple => {
        val function: (Restm, ExecutionContext) ⇒ TaskResult[_] = tuple._2
        val task = tuple._1.asInstanceOf[Task[AnyRef]]
        if (verbose) println(s"Starting task ${task.id} at ${new Date()}")
        val result = monitorFuture("StmExecutionQueue.runTask") {
          def attempt(retries: Int): Future[TaskResult[_]] = Future {
            if (verbose) println(s"Starting attempt for task ${task.id} at ${new Date()}")
            function(cluster, StmExecutionQueue.executionContext)
          }(StmExecutionQueue.taskExecutionContext).recoverWith({
            case e: Throwable if retries > 0 =>
              if (verbose) e.printStackTrace()
              attempt(retries - 1)
          })(StmExecutionQueue.taskExecutionContext)
          try {
            attempt(3)
          } catch {
            case e : Throwable ⇒ Future.failed(e)
          }
        }(StmExecutionQueue.taskExecutionContext).asInstanceOf[Future[TaskResult[AnyRef]]]
        monitorFuture("StmExecutionQueue.completeTask") {
          result
            .flatMap(result⇒task.atomic()(cluster).complete(Success(result)))(StmExecutionQueue.taskExecutionContext)
              .recoverWith({
                case e : Throwable ⇒
                  task.atomic()(cluster).complete(Failure(e))
                  Future.failed(e)
              })(StmExecutionQueue.taskExecutionContext)
        }(StmDaemons.executionContext).map(_ => {
          if (verbose) println(s"Completed task ${task.id} at ${new Date()}")
          ExecutionStatusManager.end(executorId, task)
          true
        })(StmDaemons.executionContext)
      }).getOrElse(Future.successful(false))
    })(StmDaemons.executionContext)
  }

  private def getTask(executorName: String = ExecutionStatusManager.getName)
                     (implicit ctx: STMTxnCtx): Future[Option[(Task[AnyRef], (Restm, ExecutionContext) => TaskResult[AnyRef])]] = {
    implicit def executionContext = StmDaemons.executionContext
    workQueue.take(1).map(_.map(_.asInstanceOf[Task[AnyRef]])).flatMap((task: Option[Task[AnyRef]]) => {
      task.map(task => {
        val obtainTask: Future[Option[(Restm, ExecutionContext) => TaskResult[AnyRef]]] = task.obtainTask(executorName)
        obtainTask.map(_.map(function => {
          ExecutionStatusManager.start(executorName, task)
          task -> function
        }))
      }).getOrElse(Future.successful(None))
    })
  }

  class AtomicApi()(implicit cluster: Restm) extends AtomicApiBase {
    def add(f: Task[_]): Future[Unit] = atomic {
      StmExecutionQueue.this.add(f)(_)
    }

    def add[T](f: (Restm, ExecutionContext) => TaskResult[T], ancestors: List[Task[_]] = List.empty): Future[Task[T]] = atomic {
      StmExecutionQueue.this.add[T](f, ancestors)(_)
    }

    def sync(duration: Duration) = new SyncApi(duration)

    def sync = new SyncApi(10.seconds)

    class SyncApi(duration: Duration) extends SyncApiBase(duration) {
      def add[T](f: Task[T]): Unit = sync {
        AtomicApi.this.add(f)
      }

      def add[T](f: (Restm, ExecutionContext) => TaskResult[T], ancestors: List[Task[_]] = List.empty): Task[T] = sync {
        AtomicApi.this.add[T](f, ancestors)
      }
    }

  }

  class SyncApi(duration: Duration) extends SyncApiBase(duration) {
    def add[T](f: (Restm, ExecutionContext) => TaskResult[T], ancestors: List[Task[_]] = List.empty)(implicit ctx: STMTxnCtx): Task[T] =
      sync {
        StmExecutionQueue.this.add[T](f, ancestors)
      }

    def add(f: Task[_])(implicit ctx: STMTxnCtx): Unit = sync {
      StmExecutionQueue.this.add(f)
    }
  }

}
