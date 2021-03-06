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

import java.util.concurrent.Executors

import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.scalatest.{MustMatchers, WordSpec}
import org.scalatestplus.play.OneServerPerTest
import storage.remote.RestmHttpClient

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class ServletUtilitySpec extends WordSpec with MustMatchers with OneServerPerTest {
  private val baseUrl = s"http://localhost:$port"
  implicit val cluster = new RestmHttpClient(baseUrl)(ExecutionContext.fromExecutor(Executors.newFixedThreadPool(8,
    new ThreadFactoryBuilder().setNameFormat("restm-pool-%d").build())))
  implicit val executionContext: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(8,
    new ThreadFactoryBuilder().setNameFormat("test-pool-%d").build()))


//  "Single Node Servlet" should {
//    "provide demo sort class" in {
//      StmDaemons.start()
//      StmExecutionQueue.get().verbose = true
//      StmExecutionQueue.get().registerDaemons(4)(storageService, executionContext)
//      Thread.sleep(1000) // Allow platform to start
//      Desktop.getDesktop.browse(new URI(s"http://localhost:$port/sys/logs/"))
//      Await.result(StmDaemons.join(), 300.minutes)
//      Thread.sleep(1000) // Allow rest of processes to complete
//    }
//  }

//      "Single Node Servlet" should {
//        "provide demo cluster tree" in {
//          try {
//            val timeout = 90.seconds
//            Await.result(Http((url(baseUrl) / "sys" / "init").GET OK as.String), timeout)
//            StmExecutionQueue.get().verbose = true
//            val items = Integer.MAX_VALUE
//            Thread.sleep(1000) // Allow platform to start
//
//            val treeId = UUID.randomUUID().toString
//
//            def insert(label:String, item:Seq[Any]): String = {
//              val request = (url(baseUrl) / "cluster" / treeId).addQueryParameter("label", label)
//              val requestBody = item.map(JacksonValue.simple(_).toString).mkString("\n")
//              Await.result(Http(request.PUT << requestBody OK as.String), timeout)
//            }
//            def getStrategy(): ClassificationStrategy = {
//              val request = (url(baseUrl) / "cluster" / treeId / "config")
//              val json = Await.result(Http(request.GET OK as.String), timeout)
//              JacksonValue(json).deserialize[ClassificationStrategy]().get
//            }
//            def setStrategy(item:ClassificationStrategy): String = {
//              val request = (url(baseUrl) / "cluster" / treeId / "config")
//              val requestBody = JacksonValue(item).toString
//              Await.result(Http(request.POST << requestBody OK as.String), timeout)
//            }
//            def split(item:ClassificationStrategy, clusterId:Int = 1): JsonObject = {
//              val request = (url(baseUrl) / "cluster" / treeId / clusterId / "split")
//              val requestBody = JacksonValue(item).toString
//              val result = Await.result(Http(request.POST << requestBody OK as.String), timeout)
//              val gson: Gson = new GsonBuilder().setPrettyPrinting().create()
//              gson.fromJson(result, classOf[JsonObject])
//            }
//            def query(item:Any): JsonObject = {
//              val request = (url(baseUrl) / "cluster" / treeId / "find")
//              val result = Await.result(Http(request.POST << JacksonValue(item).toString OK as.String), timeout)
//              val gson: Gson = new GsonBuilder().setPrettyPrinting().create()
//              gson.fromJson(result, classOf[JsonObject])
//            }
//            def info(): String = {
//              val request = (url(baseUrl) / "cluster" / treeId / "config")
//              Await.result(Http(request.GET OK as.String), timeout)
//            }
//
//            println(getStrategy())
//            println(setStrategy(new NoBranchStrategy))
//            println(info())
//
//            ForestCoverDataset.asTrainingSet(ForestCoverDataset.dataSet.take(items)).foreach(x⇒{
//              val (key: String,value: List[ClassificationTreeItem]) = x
//              value.grouped(100).foreach(block⇒{
//                println(insert(key, block.map(_.attributes)))
//              })
//            })
//
//            val testValue = ForestCoverDataset.dataSet.head
//            val queryString = testValue.attributes.map(x⇒x._1 + "=" + x._2).mkString("&")
//            Desktop.getDesktop.browse(new URI(s"http://localhost:$port/cluster/$treeId/find?$queryString"))
//            val queryResult = query(testValue.attributes)
//            println(queryResult)
//            val nodeId = queryResult.getAsJsonObject("path").getAsJsonPrimitive("treeId").getAsInt
//            Desktop.getDesktop.browse(new URI(s"http://localhost:$port/cluster/$treeId/$nodeId/info"))
//            val taskInfo = split(new DefaultClassificationStrategy(2))
//            println(taskInfo)
//            val taskId = taskInfo.getAsJsonPrimitive("task").getAsString
//            Desktop.getDesktop.browse(new URI(s"http://localhost:$port/task/info/$taskId"))
//            TaskUtil.awaitTask(new Task[AnyRef](new Restm.PointerType(taskId)), 100.minutes)
//            val queryResult = query(testValue.attributes)
//            println(queryResult)
//            val nodeId = queryResult.getAsJsonObject("path").getAsJsonPrimitive("treeId").getAsInt
//
//            println("About to shut down...")
//            Thread.sleep(10000)
//            Await.result(Http((url(baseUrl) / "sys" / "shutdown").GET OK as.String), timeout)
//            Await.result(StmDaemons.join(), 5.minutes)
//            Thread.sleep(1000) // Allow rest of processes to complete
//          } finally {
//            println(JacksonValue.simple(Util.getMetrics).pretty)
//            Util.clearMetrics()
//          }
//        }
//      }


}
