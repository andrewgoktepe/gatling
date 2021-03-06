/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.recorder.http.handler

import java.net.URI

import scala.collection.JavaConversions.asScalaBuffer

import io.gatling.recorder.util.URIHelper
import org.jboss.netty.channel.ChannelFutureListener
import org.jboss.netty.channel.ChannelFuture
import org.jboss.netty.handler.codec.http.{ DefaultHttpRequest, HttpRequest }

trait ScalaChannelHandler {

  implicit def function2ChannelFutureListener(thunk: ChannelFuture => Any) = new ChannelFutureListener {
    def operationComplete(future: ChannelFuture): Unit = thunk(future)
  }

  private def copyRequestWithNewUri(request: HttpRequest, uri: String): HttpRequest = {
    val newRequest = new DefaultHttpRequest(request.getProtocolVersion, request.getMethod, uri)
    newRequest.setChunked(request.isChunked)
    newRequest.setContent(request.getContent)
    for (header <- request.headers.entries) newRequest.headers.add(header.getKey, header.getValue)
    newRequest
  }

  def buildRequestWithRelativeURI(request: HttpRequest): HttpRequest = {
    val (_, pathQuery) = URIHelper.splitURI(request.getUri)
    copyRequestWithNewUri(request, pathQuery)
  }

  def buildRequestWithAbsoluteURI(request: HttpRequest, targetHostURI: URI): HttpRequest = {
    val absoluteUri = targetHostURI.resolve(request.getUri).toString
    copyRequestWithNewUri(request, absoluteUri)
  }
}
