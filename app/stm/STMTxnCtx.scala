package stm

import storage.Restm
import storage.Restm._
import util.Util

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.ClassTag

class STMTxnCtx(val cluster: Restm, val priority: Duration, prior: Option[STMTxnCtx]) {

  private[stm] val defaultTimeout: Duration = 5.seconds

  def newPtr[T <: AnyRef](value: T)(implicit executionContext: ExecutionContext): Future[PointerType] =
    txnId.flatMap(cluster.newPtr(_, Restm.value(value)).map(ptr=>{
      initCache.put(ptr, Option(value))
      ptr
    }))

  var isClosed = false

  private[stm] def commit()(implicit executionContext: ExecutionContext): Future[Unit] = Util.monitorFuture("STMTxnCtx.getCurrentValue") {
    //if(writeLocks.isEmpty) Future.successful(Unit) else
    isClosed = true
    txnId.flatMap(txnId => Future.sequence(
          writeCache
            //.filter(_=>false)
            .map(write=>write._2
              .map(newValue=>cluster.queueValue(write._1, txnId, Restm.value(newValue)))
              .getOrElse(cluster.delete(write._1, txnId)))
        ).map(_=>txnId))
      .flatMap(cluster.commit)
  }

  private[stm] def revert()(implicit executionContext: ExecutionContext): Future[Unit] = Util.monitorFuture("STMTxnCtx.getCurrentValue") {
    isClosed = true
    //if(writeLocks.isEmpty) Future.successful(Unit) else
    txnId.flatMap(cluster.reset)
  }

  private lazy val txnId = cluster.newTxn(priority)
  private[this] val writeLocks = new TrieMap[PointerType,Future[_]]()
  private[stm] val readCache: TrieMap[PointerType, Future[Option[_]]] = new TrieMap()
  private[stm] val initCache: TrieMap[PointerType, Option[AnyRef]] = new TrieMap()
  private[stm] val writeCache: TrieMap[PointerType, Option[AnyRef]] = new TrieMap()

  private[stm] def write[T <: AnyRef : ClassTag](id: PointerType, value: T)(implicit executionContext: ExecutionContext): Future[Unit] = txnId.flatMap(txnId => Util.monitorFuture("STMTxnCtx.write") {
    require(!isClosed)
    readOpt(id).flatMap(prior => {
      if (value != prior.orNull) {
        lock(id).flatMap(x => {
          if(!isClosed) {
            writeCache.put(id, Option(value))
            Future.successful(Unit)
          } else {
            System.err.println(s"Post-commit write for $id")
            cluster.queueValue(id, txnId, Restm.value(value))
          }
        })
      } else {
        Future.successful(Unit)
      }
    })
  })

  def delete(id: PointerType)(implicit executionContext: ExecutionContext): Future[Unit]  = txnId.flatMap(txnId => Util.monitorFuture("STMTxnCtx.delete") {
    require(!isClosed)
    readOpt(id).flatMap(prior => {
      if (prior.isDefined) {
        lock(id).flatMap(x => {
          if(!isClosed) {
            writeCache.put(id, None)
            System.err.println(s"Post-commit delete for $id")
            Future.successful(Unit)
          } else {
            cluster.delete(id, txnId)
          }
        })
      } else {
        Future.successful(Unit)
      }
    })
  })

  private[stm] def readOpt[T <: AnyRef : ClassTag](id: PointerType)
                                                  (implicit executionContext: ExecutionContext): Future[Option[T]] = //Util.monitorFuture("STMTxnCtx.readOpt")
  {
    require(!isClosed)
    writeCache.get(id).orElse(initCache.get(id))
    .map(x => Future.successful(x.map(_.asInstanceOf[T]))).getOrElse(
      readCache.getOrElseUpdate(id,
        txnId.flatMap(txnId => {
//          def previousValue: Option[T] = prior.flatMap(_.readCache.get(id)
//            .filter(_.isCompleted)
//            .map(_.recover({ case _ => None }))
//            .flatMap(Await.result(_, 0.millisecond))
//            .map(_.asInstanceOf[T]))
//          val previousTime: Option[TimeStamp] = prior.map(_.txnId)
//            .map(_.recover({ case _ => None }))
//            .filter(_.isCompleted)
//            .map(Await.result(_, 0.millisecond))
//            .map(_.asInstanceOf[TimeStamp])
//          cluster.getPtr(id, txnId, previousTime).map(_.flatMap(_.deserialize[T]()).orElse(previousValue))
          cluster.getPtr(id, txnId).map(_.flatMap(_.deserialize[T]()))
        })
      ).map(_.map(_.asInstanceOf[T])))
  }

  private[stm] def lock(id: PointerType)(implicit executionContext: ExecutionContext): Future[Boolean] = Util.monitorFuture("STMTxnCtx.lock") {
    writeLocks.getOrElseUpdate(id,
      txnId.flatMap(txnId => {
        require(!isClosed)
        cluster.lock(id, txnId)
      }).map(_.isEmpty)
      .map(success => if (!success) throw new RuntimeException(s"Lock failed: $id in txn $txnId") else success))
      .asInstanceOf[Future[Boolean]]
  }
  override def toString = {
    "txn@" + Option(txnId).filter(_.isCompleted).map(future => Await.result(future, 1.second))
      .map(_.toString).getOrElse("???")
  }
}
