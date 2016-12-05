import java.util.UUID
import java.util.concurrent.Executors

import com.amazonaws.auth.{AWSCredentials, AWSStaticCredentialsProvider, BasicAWSCredentials}
import org.scalatest.{MustMatchers, WordSpec}
import stm.lib0._
import storage.Restm._
import storage.util._
import storage.{RestmActors, _}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

class ColdStorageIntegrationSpec extends WordSpec with MustMatchers {
  implicit val executor: ExecutionContext = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  "HeapColdStorage" should {
    "persist and restore data" in {
      implicit val coldStorage: ColdStorage = new HeapColdStorage
      val ids = randomUUIDs.take(5).toList
      addItems(ids)
      deleteItems(ids)
      addItems(ids)
    }
  }

  "DynamoLocalColdStorage" should {
    "persist and restore data" in {
      val credentials: AWSCredentials = new BasicAWSCredentials("ABC","XYZ")
      implicit val coldStorage: ColdStorage = new DynamoColdStorage(
        tableName = "testDynamoLocalColdStorage",
        endpoint = "http://localhost:8000",
        awsCredentialsProvider = new AWSStaticCredentialsProvider(credentials))
      val ids = randomUUIDs.take(5).toList
      addItems(ids)
      Thread.sleep(1000)
      deleteItems(ids)
      Thread.sleep(1000)
      addItems(ids)
    }
  }

  def randomUUIDs: Stream[String] = Stream.continually(UUID.randomUUID().toString.take(8))
  val collection = TreeSet.static[String](new PointerType("test/ColdStorageIntegrationSpec/" + UUID.randomUUID().toString))

  def addItems(items : List[String] = randomUUIDs.take(5).toList)(implicit coldStorage: ColdStorage, executor: ExecutionContext) = {
    val internal: RestmActors = new RestmActors(coldStorage)
    implicit val cluster = new RestmImpl(internal)
    for (item <- items) {
      collection.atomic.sync.contains(item) mustBe false
      collection.atomic.sync.add(item)
      collection.atomic.sync.contains(item) mustBe true
    }
    Await.result(internal.flushColdStorage(), 1.minutes)
    items
  }

  def deleteItems(items:List[String])(implicit coldStorage: ColdStorage, executor: ExecutionContext) = {
    val internal: RestmActors = new RestmActors(coldStorage)
    implicit val cluster = new RestmImpl(internal)
    for (item <- items) {
      collection.atomic.sync.contains(item) mustBe true
      collection.atomic.sync.remove(item)
      collection.atomic.sync.contains(item) mustBe false
    }
    Await.result(internal.flushColdStorage(), 1.minutes)
    items
  }
}
