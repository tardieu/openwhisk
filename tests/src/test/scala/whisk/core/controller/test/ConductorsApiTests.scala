/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.controller.test

import java.time.Instant

import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonMarshaller
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonUnmarshaller
import akka.http.scaladsl.server.Route

import spray.json._
import spray.json.DefaultJsonProtocol._

import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.connector.ActivationMessage
import whisk.core.controller.WhiskActionsApi
import whisk.core.entity._
import whisk.core.entity.size._
import whisk.core.entitlement.Collection
import whisk.core.loadBalancer.LoadBalancer
import whisk.http.ErrorResponse
import whisk.http.Messages

/**
 * Tests Conductor Actions API.
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 */
@RunWith(classOf[JUnitRunner])
class ConductorsApiTests extends ControllerTestCommon with WhiskActionsApi {

  /** Actions API tests */
  behavior of "Conductor Actions API"

  override val loadBalancer = new FakeLoadBalancerService(whiskConfig)

  val creds = WhiskAuthHelpers.newIdentity()
  val namespace = EntityPath(creds.subject.asString)
  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
  val conductorName = MakeName.next("action_tests")
  val actionName = MakeName.next("echo")
  val duration = 42
  def filter(obj: JsObject) = JsObject(obj.fields.filterKeys(Set("name", "namespace", "subject", "reponse")))

  it should "invoke a conductor action, blocking and validate result" in {
    implicit val tid = transid()
    val conductor =
      WhiskAction(namespace, conductorName, jsDefault("??"), annotations = Parameters("conductor", "true"))
    val activation = WhiskActivation(
      conductor.namespace,
      conductor.name,
      creds.subject,
      activationIdFactory.make(),
      start = Instant.now,
      end = Instant.now.plusMillis(duration),
      response = ActivationResponse.success(Some(JsObject("test" -> "yes".toJson))))
    put(entityStore, conductor)

    try {
      // do not store the activation in the db, instead register it as the response to generate on active ack
      loadBalancer.respond(1.milliseconds, activation)

      Post(s"$collectionPath/${conductor.name}?blocking=true") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        filter(response) shouldBe filter(activation.toExtendedJson)
        response.fields("duration") shouldBe JsNumber(duration)
        val annotations = response.fields("annotations").convertTo[Parameters]
        annotations.asBool("conductor") shouldBe Some(true)
        annotations.asString("kind") shouldBe Some("sequence")
        annotations.asBool("topmost") shouldBe Some(true)
        annotations.get("limits") should not be None
      }
    } finally {
      loadBalancer.clear
    }
  }

  it should "invoke a composition, blocking and validate result" in {
    implicit val tid = transid()
    val conductor =
      WhiskAction(namespace, conductorName, jsDefault("??"), annotations = Parameters("conductor", "true"))
    val activation1 = WhiskActivation(
      conductor.namespace,
      conductor.name,
      creds.subject,
      activationIdFactory.make(),
      start = Instant.now,
      end = Instant.now.plusMillis(duration),
      response = ActivationResponse.success(
        Some(JsObject("action" -> JsString(s"$namespace/$actionName"), "params" -> JsObject()))))
    val action = WhiskAction(namespace, actionName, jsDefault("??"))
    val activation2 = WhiskActivation(
      action.namespace,
      action.name,
      creds.subject,
      activationIdFactory.make(),
      start = Instant.now,
      end = Instant.now.plusMillis(duration),
      response = ActivationResponse.success(Some(JsObject())))
    val activation3 = WhiskActivation(
      conductor.namespace,
      conductor.name,
      creds.subject,
      activationIdFactory.make(),
      start = Instant.now,
      end = Instant.now.plusMillis(duration),
      response = ActivationResponse.success(Some(JsObject("test" -> "yes".toJson))))
    put(entityStore, conductor)
    put(entityStore, action)

    try {
      loadBalancer.respond(1.milliseconds, activation1)
      loadBalancer.respond(1.milliseconds, activation2)
      loadBalancer.respond(1.milliseconds, activation3)

      Post(s"$collectionPath/${conductor.name}?blocking=true") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        filter(response) shouldBe filter(activation3.toExtendedJson)
        response.fields("duration") shouldBe JsNumber(duration * 3)
        val annotations = response.fields("annotations").convertTo[Parameters]
        annotations.asBool("conductor") shouldBe Some(true)
        annotations.asString("kind") shouldBe Some("sequence")
        annotations.asBool("topmost") shouldBe Some(true)
        annotations.get("limits") should not be None
      }
    } finally {
      loadBalancer.clear
    }
  }
}

class FakeLoadBalancerService(config: WhiskConfig)(implicit ec: ExecutionContext)
    extends DegenerateLoadBalancerService(config) {
  import scala.concurrent.blocking

  private val activations = mutable.Queue.empty[(FiniteDuration, WhiskActivation)]

  def respond(timeout: FiniteDuration, activation: WhiskActivation) {
    activations += ((timeout, activation))
  }

  def clear {
    activations.clear
  }

  override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] =
    Future.successful {
      Try(activations.dequeue) match {
        case Success((timeout, activation)) =>
          if (!activation.name.equals(action.name)) {
            Future.failed(new IllegalArgumentException("Conductor test cannot find activation"))
          } else {
            Future {
              blocking {
                println(s"load balancer active ack stub: waiting for $timeout...")
                Thread.sleep(timeout.toMillis)
                println(".... done waiting")
              }
              Right(activation.copy(activationId = msg.activationId))
            }
          }
        case Failure(_) => Future.failed(new IllegalArgumentException("Conductor test cannot find activation"))
      }
    }
}
