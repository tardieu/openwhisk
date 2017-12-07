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

package whisk.core.containerpool.docker.test

import java.io.File

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.language.reflectiveCalls // Needed to invoke publicIpAddressFromFile() method of structural dockerClientForIp extension

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers

import common.StreamLogging
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.containerpool.ContainerId
import whisk.core.containerpool.ContainerAddress
import whisk.core.containerpool.docker.DockerClientWithFileAccess

@RunWith(classOf[JUnitRunner])
class DockerClientWithFileAccessTestsIp extends FlatSpec with Matchers with StreamLogging with BeforeAndAfterEach {

  override def beforeEach = stream.reset()

  implicit val transid = TransactionId.testing
  val id = ContainerId("Id")

  def await[A](f: Future[A], timeout: FiniteDuration = 500.milliseconds) = Await.result(f, timeout)

  val dockerCommand = "docker"
  val networkInConfigFile = "networkConfig"
  val networkInDockerInspect = "networkInspect"
  val ipInConfigFile = ContainerAddress("10.0.0.1")
  val ipInDockerInspect = ContainerAddress("10.0.0.2")
  val dockerConfig =
    JsObject(
      "NetworkSettings" ->
        JsObject(
          "Networks" ->
            JsObject(networkInConfigFile ->
              JsObject("IPAddress" -> JsString(ipInConfigFile.host)))))

  /** Returns a DockerClient with mocked results */
  def dockerClient(execResult: Future[String] = Future.successful(ipInDockerInspect.host),
                   readResult: Future[JsObject] = Future.successful(dockerConfig)) =
    new DockerClientWithFileAccess()(global) {
      override val dockerCmd = Seq(dockerCommand)
      override def executeProcess(args: String*)(implicit ec: ExecutionContext) = execResult
      override def configFileContents(configFile: File) = readResult
      // Make protected ipAddressFromFile available for testing - requires reflectiveCalls
      def publicIpAddressFromFile(id: ContainerId, network: String): Future[ContainerAddress] =
        ipAddressFromFile(id, network)
    }

  behavior of "DockerClientWithFileAccess - ipAddressFromFile"

  it should "throw NoSuchElementException if specified network is not in configuration file" in {
    val dc = dockerClient()

    a[NoSuchElementException] should be thrownBy await(dc.publicIpAddressFromFile(id, "foo network"))
  }

  behavior of "DockerClientWithFileAccess - inspectIPAddress"

  it should "read from config file" in {
    val dc = dockerClient()

    await(dc.inspectIPAddress(id, networkInConfigFile)) shouldBe ipInConfigFile
    logLines.foreach { _ should not include (s"${dockerCommand} inspect") }
  }

  it should "fall back to 'docker inspect' if config file cannot be read" in {
    val dc = dockerClient(readResult = Future.failed(new RuntimeException()))

    await(dc.inspectIPAddress(id, networkInDockerInspect)) shouldBe ipInDockerInspect
    logLines.head should include(s"${dockerCommand} inspect")
  }

  it should "throw NoSuchElementException if specified network does not exist" in {
    val dc = dockerClient(execResult = Future.successful("<no value>"))

    a[NoSuchElementException] should be thrownBy await(dc.inspectIPAddress(id, "foo network"))
  }
}

@RunWith(classOf[JUnitRunner])
class DockerClientWithFileAccessTestsOom extends FlatSpec with Matchers with StreamLogging with BeforeAndAfterEach {
  override def beforeEach = stream.reset()

  implicit val transid = TransactionId.testing
  val id = ContainerId("Id")

  def await[A](f: Future[A], timeout: FiniteDuration = 500.milliseconds) = Await.result(f, timeout)

  def dockerClient(readResult: Future[JsObject]) =
    new DockerClientWithFileAccess()(global) {
      override val dockerCmd = Seq("docker")
      override def configFileContents(configFile: File) = readResult
    }

  def stateObject(oom: Boolean) = JsObject("State" -> JsObject("OOMKilled" -> oom.toJson))

  behavior of "DockerClientWithFileAccess - isOomKilled"

  it should "return the state of the container respectively" in {
    val dcTrue = dockerClient(Future.successful(stateObject(true)))
    await(dcTrue.isOomKilled(id)) shouldBe true

    val dcFalse = dockerClient(Future.successful(stateObject(false)))
    await(dcFalse.isOomKilled(id)) shouldBe false
  }

  it should "default to 'false' if the json structure is unparseable" in {
    val dc = dockerClient(Future.successful(JsObject()))
    await(dc.isOomKilled(id)) shouldBe false
  }
}
