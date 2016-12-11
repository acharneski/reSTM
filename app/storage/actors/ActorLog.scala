package storage.actors

import java.io.{File, FileOutputStream, PrintWriter}

import scala.concurrent.{ExecutionContext, Future}


object ActorLog extends ActorQueue {

  private def now = System.currentTimeMillis

  private val file: File = new File(s"logs/actors.$now.log")
  private lazy val writer: PrintWriter = new PrintWriter(new FileOutputStream(file))
  var enabled = false

  override def log(str: String)(implicit exeCtx: ExecutionContext): Future[Unit] = if(!enabled) Future.successful(Unit) else withActor {
    writer.println(str)
    writer.flush()
  }
}