package storage.util

import javax.inject.Singleton

import storage.Restm.{PointerType, TimeStamp, ValueType}
import storage._

import scala.concurrent.Future

@Singleton
class RestmInject extends RestmPtr with RestmInternalPtr {
  override lazy val inner: Restm with RestmInternal = new RestmImpl with RestmActors

  override def queue(id: PointerType, time: TimeStamp, value: ValueType): Future[Unit] = inner.queue(id, time, value)
}
