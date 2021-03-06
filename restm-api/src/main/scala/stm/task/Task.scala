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

import java.util.UUID
import java.util.concurrent.{Executors, ScheduledExecutorService, ScheduledFuture, TimeUnit}

import com.fasterxml.jackson.annotation.JsonIgnore
import com.google.common.util.concurrent.ThreadFactoryBuilder
import stm._
import stm.task.Task.TaskResult
import stm.task.TaskStatus.Failed
import storage.Restm
import storage.Restm.PointerType
import storage.types.KryoValue
import util.Util

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

object Task {

  type TaskFunction[T] = (Restm, ExecutionContext) => TaskResult[T]

  private implicit def executionContext = StmDaemons.executionContext

  private[stm] val scheduledThreadPool: ScheduledExecutorService = Executors.newScheduledThreadPool(2,
    new ThreadFactoryBuilder().setNameFormat("task-monitor-pool-%d").build())
  private[Task] val futureCache = new TrieMap[AnyRef, Future[_]]()

  def create[T](f: TaskFunction[T], ancestors: List[Task[_]] = List.empty)(implicit ctx: STMTxnCtx): Future[Task[T]] =
    STMPtr.dynamic[TaskData[T]](new TaskData[T](Option(KryoValue(f)), triggers = ancestors)).map(new Task(_))

  sealed trait TaskResult[T] {
  }

  final case class TaskSuccess[T](value: T) extends TaskResult[T]

  final case class TaskContinue[T](queue: StmExecutionQueue, newFunction: TaskFunction[T], newTriggers: List[Task[T]] = List.empty) extends TaskResult[T]

}

import stm.task.Task._

object TaskStatus {

  case class Running(executor: String) extends TaskStatus {
    override def toString: String = s"${getClass.getSimpleName}($executor)"
  }

  case class Failed(ex: String) extends TaskStatus {
    override def toString: String = s"${getClass.getSimpleName}($ex)"
  }

  case class Orphan(ex: String) extends TaskStatus {
    override def toString: String = s"${getClass.getSimpleName}($ex)"
  }

  object Blocked extends TaskStatus

  object Queued extends TaskStatus

  object Success extends TaskStatus

  object Unknown extends TaskStatus

  object Ex_NotDefined extends TaskStatus

}

sealed class TaskStatus {
  def getName: String = getClass.getSimpleName

  override def toString: String = s"${getClass.getSimpleName}"
}

case class TaskStatusTrace(id: PointerType, status: TaskStatus, children: List[TaskStatusTrace] = List.empty)

class Task[T](val root: STMPtr[TaskData[T]]) extends Identifiable {
  private implicit def executionContext = StmDaemons.executionContext

  def this(ptr: PointerType) = this(new STMPtr[TaskData[T]](ptr))

  def sync(duration: Duration) = new SyncApi(duration)

  //  def this()(implicit ctx: STMTxnCtx, classTag: ClassTag[T]) = this(STMPtr.dynamicSync(new TaskData[T]()))
  //  def this(ptr:PointerType)(implicit executionContext: ExecutionContext, classTag: ClassTag[T]) = this(new STMPtr[TaskData[T]](ptr))

  def sync = new SyncApi(10.seconds)

  def id: String = root.id.toString

  def map[U](queue: StmExecutionQueue, function: (T, Restm, ExecutionContext) => TaskResult[U])(implicit ctx: STMTxnCtx): Future[Task[U]] = {
    val func: TaskFunction[U] = wrapMap(function)
    Task.create(func, List(Task.this)).flatMap(task => task.initTriggers(queue).map(_ => task))
  }

  def wrapMap[U](function: (T, Restm, ExecutionContext) => U): (Restm, ExecutionContext) => U = {
    (c, e) => function(Task.this.atomic()(c).sync.result(), c, e)
  }

  def atomic(priority: Duration = 0.seconds)(implicit cluster: Restm) = new AtomicApi(priority)

  def initTriggers(queue: StmExecutionQueue)(implicit ctx: STMTxnCtx): Future[Unit.type] = {
    root.read.flatMap(_.initTriggers(Task.this, queue)).map(_ => Unit)
  }

  def future(implicit cluster: Restm): Future[T] = futureCache.getOrElseUpdate((cluster.toString, this.id), {
    implicit def executionContext = StmDaemons.executionContext
    val promise = Promise[T]()
    val schedule: ScheduledFuture[_] = scheduledThreadPool.scheduleAtFixedRate(new Runnable {
      override def run(): Unit = Task.this.atomic().isComplete.map(isComplete => {
        //System.out.println(s"Checked ${Task.this.id} - isComplete = $isComplete")
        if (isComplete) {
          promise.completeWith(Task.this.atomic().result())
        }
      })
    }, 1, 1, TimeUnit.SECONDS)
    promise.future.onComplete(_ => schedule.cancel(false))
    promise.future
  }).asInstanceOf[Future[T]]

  def obtainTask(executorId: String = UUID.randomUUID().toString)(implicit ctx: STMTxnCtx): Future[Option[TaskFunction[T]]] = {
    require(null != root)
    root.read().flatMap(currentState => {
      val task: Option[TaskFunction[T]] = currentState.kryoTask.flatMap(x => x.deserialize())
      task.filter(_ => {
        currentState.executorId.isEmpty && currentState.result.isEmpty && currentState.exception.isEmpty
      }).map(task => {
        root.write(currentState.copy(executorId = Option(executorId))).map(_ => task).map(Option(_))
      }).getOrElse(Future.successful(None))
    })
  }

  def addSubscriber(task: Task[_], queue: StmExecutionQueue)(implicit ctx: STMTxnCtx): Future[Boolean] = {
    root.read.flatMap(prev => {
      val newSubscribers: Map[Task[_], StmExecutionQueue] = Map[Task[_], StmExecutionQueue](task -> queue)
      val prevSubscribers: List[TaskSubscription] = prev.subscribers
      root.write(prev.copy(subscribers = prevSubscribers ++ newSubscribers.map(e => TaskSubscription(e._1, e._2)).toList)).map(_ => prev)
    }).map(currentState => currentState.result.isDefined || currentState.exception.isDefined)
  }

  def data()(implicit ctx: STMTxnCtx): Future[TaskData[T]] = Util.chainEx("Error getting result") {
    implicit def executionContext = StmDaemons.executionContext
    root.read()
  }

  @JsonIgnore def getStatusTrace(queue: StmExecutionQueue)(implicit ctx: STMTxnCtx): Future[TaskStatusTrace] = {
    //System.out.println(s"Current tasks: ${queuedTasks.size}")
    val threads: Iterable[Thread] = Thread.getAllStackTraces.asScala.keys
    getStatusTrace(queue, threads)
  }

  def getStatusTrace(queue: StmExecutionQueue, threads: Iterable[Thread])(implicit ctx: STMTxnCtx): Future[TaskStatusTrace] = {
    require(null != queue)
    def visit(currentState: TaskData[T], id: PointerType): TaskStatusTrace = {
      val status: TaskStatus = if (currentState.result.isDefined) {
        TaskStatus.Success
      } else if (currentState.exception.isDefined) {
        TaskStatus.Failed(currentState.exception.get.toString)
      } else if (currentState.executorId.isDefined) {
        if (checkExecutor(currentState.executorId.get, id, threads)) {
          TaskStatus.Running(currentState.executorId.get)
        } else {
          TaskStatus.Orphan("Not Found: " + currentState.executorId.get)
        }
      } else {
        TaskStatus.Unknown
      }
      TaskStatusTrace(id, status, List.empty)
    }

    root.readOpt().flatMap(currentState => {
      val statusTrace: TaskStatusTrace = currentState.map(visit(_, root.id))
        .getOrElse(TaskStatusTrace(root.id, TaskStatus.Ex_NotDefined))
      lazy val children: Future[List[TaskStatusTrace]] = currentState.map(_.triggers.map(_.getStatusTrace(queue, threads)))
        .map(Future.sequence(_)).getOrElse(Future.successful(List.empty))
      statusTrace.status match {
        case TaskStatus.Success => Future.successful(statusTrace)
        case TaskStatus.Unknown => children.flatMap(children => {
          val pending = children.filter(_.status match {
            case TaskStatus.Success => false
            case _: Failed => false
            case _ => true
          })
          if (pending.isEmpty) {
            queue.workQueue.contains(id).map(contains => {
              if (contains) {
                statusTrace.copy(status = TaskStatus.Queued)
              } else {
                statusTrace.copy(status = TaskStatus.Orphan("Not In Queue"))
              }
            })
          } else {
            Future.successful(statusTrace.copy(status = TaskStatus.Blocked, children = children))
          }
        })
        case _: Failed => Future.successful(statusTrace)
        case TaskStatus.Ex_NotDefined => Future.successful(statusTrace)
        case _ => children.map(x => statusTrace.copy(children = x))
      }
    })
  }

  def checkExecutor(executorId: String, id: PointerType, threads: Iterable[Thread]): Boolean = {
    val threadName = executorId.split(":")(1)

    def isExecutorAlive = threads.filter(_.isAlive).exists(_.getName == threadName)

    def isCurrentTask = ExecutionStatusManager.check(executorId, id.toString)

    isCurrentTask && isExecutorAlive
  }

  def complete(result: Try[TaskResult[T]])(implicit ctx: STMTxnCtx): Future[Unit] = {
    root.read().flatMap(currentState => {
      result.transform[Future[TaskData[T]]](
        taskResult => Try {
          taskResult match {
            case TaskSuccess(success) =>
              Future.successful(currentState.copy(result = Option(success)))
            case TaskContinue(queue, next, newTriggers) =>
              currentState
                .copy(triggers = newTriggers, executorId = None)
                .newTask(next)
                .initTriggers(Task.this, queue)
          }
        },
        exception => Try {
          Future.successful(currentState.copy(exception = Option(exception)))
        }
      ).get.flatMap(update => root.write(update)).map(_ => currentState.subscribers)
    }).flatMap(subscribers => {
      Future.sequence(subscribers.map(subscription => {
        subscription.task.canRun().flatMap(complete => {
          if (complete) {
            subscription.queue.add(subscription.task)
          } else {
            Future.successful(Unit)
          }
        })
      })).map(_ => Unit)
    })
  }

  def canRun()(implicit ctx: STMTxnCtx): Future[Boolean] = {
    root.read().flatMap(currentState => {
      Future.sequence(currentState.triggers.map(_.isComplete())).map(_.reduceOption(_ && _).getOrElse(true))
    })
  }

  def isComplete()(implicit ctx: STMTxnCtx): Future[Boolean] = {
    root.readOpt().map(_.exists(currentState => currentState.result.isDefined || currentState.exception.isDefined))
  }

  override def equals(other: Any): Boolean = other match {
    case that: Task[T] => root == that.root
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(root)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }

  private def this() = this(new PointerType)

  class AtomicApi(priority: Duration = 0.seconds)(implicit cluster: Restm) extends AtomicApiBase(priority) {
    def result() = atomic {
      Task.this.data()(_)
    }.map(currentState => currentState.exception.map(throw _).orElse(currentState.result).getOrElse(throw new RuntimeException(s"Task ${root.id} not complete")))

    def isComplete: Future[Boolean] = atomic {
      Task.this.isComplete()(_)
    }

    def complete(result: Try[TaskResult[T]]): Future[Unit] = atomic {
      Task.this.complete(result)(_)
    }

    def getStatusTrace(queue: StmExecutionQueue): Future[TaskStatusTrace] = atomic {
      Task.this.getStatusTrace(queue)(_)
    }

    def obtainTask(executorId: String = UUID.randomUUID().toString): Future[Option[TaskFunction[T]]] = atomic {
      Task.this.obtainTask(executorId)(_)
    }

    def map[U](queue: StmExecutionQueue, function: (T, Restm, ExecutionContext) => TaskResult[U]): Future[Task[U]] = atomic {
      Task.this.map(queue, function)(_)
    }

    def sync(duration: Duration) = new SyncApi(duration)

    def sync = new SyncApi(10.seconds)

    class SyncApi(duration: Duration) extends SyncApiBase(duration) {
      def result(): T = sync {
        AtomicApi.this.result()
      }

      def isComplete: Boolean = sync {
        AtomicApi.this.isComplete
      }

      def complete(result: Try[TaskResult[T]]): Unit = sync {
        AtomicApi.this.complete(result)
      }

      def getStatusTrace(queue: StmExecutionQueue): TaskStatusTrace = sync {
        AtomicApi.this.getStatusTrace(queue)
      }

      def obtainTask(executorId: String = UUID.randomUUID().toString): Option[TaskFunction[T]] = sync {
        AtomicApi.this.obtainTask(executorId)
      }

      def map[U](queue: StmExecutionQueue, function: (T, Restm, ExecutionContext) => TaskResult[U]): Task[U] = sync {
        AtomicApi.this.map(queue, function)
      }
    }

  }

  class SyncApi(duration: Duration) extends SyncApiBase(duration) {
    def data()(implicit ctx: STMTxnCtx) = sync {
      Task.this.data()
    }

    def map[U](queue: StmExecutionQueue, function: (T, Restm, ExecutionContext) => TaskResult[U])(implicit ctx: STMTxnCtx): Task[U] = sync {
      Task.this.map(queue, function)
    }

    def isComplete()(implicit ctx: STMTxnCtx): Boolean = sync {
      Task.this.isComplete()
    }

    def complete(result: Try[TaskResult[T]])(implicit ctx: STMTxnCtx): Unit = sync {
      Task.this.complete(result)
    }

    def getStatusTrace(queue: StmExecutionQueue)(implicit ctx: STMTxnCtx): TaskStatusTrace = sync {
      Task.this.getStatusTrace(queue)
    }

    def obtainTask(executorId: String = UUID.randomUUID().toString)(implicit ctx: STMTxnCtx): Option[TaskFunction[T]] = sync {
      Task.this.obtainTask(executorId)
    }
  }

}

case class TaskSubscription(task: Task[_], queue: StmExecutionQueue)

case class TaskData[T](
                        kryoTask: Option[KryoValue[TaskFunction[T]]] = None,
                        executorId: Option[String] = None,
                        result: Option[T] = None,
                        exception: Option[Throwable] = None,
                        triggers: List[Task[_]] = List.empty,
                        subscribers: List[TaskSubscription] = List.empty
                      ) {
  private implicit def executionContext = StmDaemons.executionContext

  def newTask(task: TaskFunction[T]): TaskData[T] = {
    this.copy(kryoTask = Option(KryoValue(task)))
  }

  def initTriggers(t: Task[T], queue: StmExecutionQueue)(implicit ctx: STMTxnCtx): Future[TaskData[T]] = {
    Future.sequence(this.triggers.map(_.addSubscriber(t, queue))).map(_.reduceOption(_ && _).getOrElse(true)).flatMap(allTriggersTripped => {
      if (allTriggersTripped) {
        queue.add(t).map(_ => TaskData.this)
      } else {
        Future.successful(TaskData.this)
      }
    })
  }
}
