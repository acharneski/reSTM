package storage.util

import java.nio.charset.Charset
import java.util.concurrent.{LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}

import dispatch.{as, url, _}
import storage.Restm._
import storage.{LockedException, Restm, RestmImpl, RestmInternal}

import scala.concurrent.{ExecutionContext, ExecutionException, Future}


class RestmProxy(val baseUrl:String) extends Restm {
  implicit val executionContext = ExecutionContext.fromExecutor(new ThreadPoolExecutor(4,4,10,TimeUnit.SECONDS, new LinkedBlockingQueue[Runnable]()))

  val utf8: Charset = Charset.forName("UTF-8")

  override def newTxn(priority:Int): Future[TimeStamp] = {
    Http((url(baseUrl) / "txn").addQueryParameter("priority",priority.toString) OK as.String)(executionContext)
      .map(new TimeStamp(_))(executionContext)
  }

  override def lock(id: PointerType, time: TimeStamp): Future[Option[TimeStamp]] = {
    Http((url(baseUrl) / "ptr" / id.toString).addQueryParameter("version", time.toString).POST > { response => {
      response.getStatusCode match {
        case 200 => None
        case 409 => Option(new TimeStamp(response.getResponseBody))
      }
    }})(executionContext)
  }

  override def reset(id: TimeStamp): Future[Unit] = {
    Http((url(baseUrl) / "txn" / id.toString).DELETE OK as.String)(executionContext)
      .map(_=>{})(executionContext)
  }

  override def commit(id: TimeStamp): Future[Unit] = {
    Http((url(baseUrl) / "txn" / id.toString).POST OK as.String)(executionContext).map(_=>{})(executionContext)
  }

  override def getPtr(id: PointerType): Future[Option[ValueType]] = {
    Http(url(baseUrl) / "ptr" / id.toString > {response => {
      response.getStatusCode match {
        case 200 => Option(new ValueType(response.getResponseBody))
        case 404 => None
      }
    }})(executionContext)
  }

  override def getPtr(id: PointerType, time: TimeStamp, ifModifiedSince:Option[TimeStamp]): Future[Option[ValueType]] = {
    var req: Req = (url(baseUrl) / "ptr" / id.toString).addQueryParameter("version", time.toString)
    req = ifModifiedSince.map(ifModifiedSince=>req.addQueryParameter("ifModifiedSince", ifModifiedSince.toString)).getOrElse(req)
    Http(req > { response => {
      response.getStatusCode match {
        case 200 => Option(new ValueType(response.getResponseBody))
        case 304 => None
        case 404 => None
      }
    }})(executionContext)
  }

  override def newPtr(version: TimeStamp, value: ValueType): Future[PointerType] = {
    Http((url(baseUrl) / s"ptr").addQueryParameter("version", version.toString).PUT << value.toString OK as.String)(executionContext)
      .map(new PointerType(_))(executionContext)
  }

  override def queue(id: PointerType, time: TimeStamp, value: ValueType): Future[Unit] = {
    Http((url(baseUrl) / "ptr" / id.toString).addQueryParameter("version", time.toString).PUT << value.toString OK as.String)(executionContext)
      .map(_=>{})(executionContext)
  }

}
