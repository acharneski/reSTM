import java.util.UUID

import org.scalatestplus.play._
import play.api.test.Helpers._
import play.api.test._

class ApplicationSpec extends PlaySpec with OneAppPerTest {

  "HomeController" should {
    "render the index page" in {
      val home = route(app, FakeRequest(GET, "/")).get
      status(home) mustBe OK
      contentType(home) mustBe Some("text/html")
      contentAsString(home) must include("Your new application is ready.")
    }
  }

  "TxnController" should {

    "write values on commit" in {
      val txnA: String = contentAsString(route(app, FakeRequest(GET, "/txn")).get)
      require(java.lang.Long.parseLong(txnA) > 0)

      val ptrId: String = UUID.randomUUID().toString
      route(app, FakeRequest(GET, s"/ptr/$ptrId?version=$txnA")).map(status(_)) mustBe Some(404)
      route(app, FakeRequest(POST, s"/ptr/$ptrId?version=$txnA")).map(status(_)) mustBe Some(200)
      route(app, FakeRequest(PUT, s"/ptr/$ptrId?version=$txnA").withTextBody("TestData")).map(status(_)) mustBe Some(200)
      route(app, FakeRequest(POST, s"/txn/$txnA").withTextBody("TestData")).map(status(_)) mustBe Some(200)

      val txnB: String = contentAsString(route(app, FakeRequest(GET, "/txn")).get)
      route(app, FakeRequest(GET, s"/ptr/$ptrId?version=$txnB")).map(status(_)) mustBe Some(200)
      route(app, FakeRequest(GET, s"/ptr/$ptrId?version=$txnB")).map(contentAsString(_)) mustBe Some("TestData")
    }

    "discard values on rollback" in {
      val txnA: String = contentAsString(route(app, FakeRequest(GET, "/txn")).get)
      require(java.lang.Long.parseLong(txnA) > 0)

      val ptrId: String = UUID.randomUUID().toString
      route(app, FakeRequest(GET, s"/ptr/$ptrId?version=$txnA")).map(status(_)) mustBe Some(404)
      route(app, FakeRequest(POST, s"/ptr/$ptrId?version=$txnA")).map(status(_)) mustBe Some(200)
      route(app, FakeRequest(PUT, s"/ptr/$ptrId?version=$txnA").withTextBody("TestData")).map(status(_)) mustBe Some(200)
      route(app, FakeRequest(DELETE, s"/txn/$txnA").withTextBody("TestData")).map(status(_)) mustBe Some(200)

      val txnB: String = contentAsString(route(app, FakeRequest(GET, "/txn")).get)
      route(app, FakeRequest(GET, s"/ptr/$ptrId?version=$txnB")).map(status(_)) mustBe Some(404)
    }

  }

}
