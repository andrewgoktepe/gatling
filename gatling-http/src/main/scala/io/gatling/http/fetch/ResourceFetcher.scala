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
package io.gatling.http.fetch

import com.ning.http.client.Request
import com.ning.http.client.uri.UriComponents
import io.gatling.core.util.CacheHelper
import io.gatling.http.request._
import io.gatling.http.util.HttpHelper._

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.breakOut
import scala.collection.mutable

import com.typesafe.scalalogging.slf4j.StrictLogging
import io.gatling.core.akka.BaseActor
import io.gatling.core.config.GatlingConfiguration.configuration
import io.gatling.core.filter.Filters
import io.gatling.core.result.message.{ OK, Status }
import io.gatling.core.session._
import io.gatling.core.util.StringHelper._
import io.gatling.core.util.TimeHelper.nowMillis
import io.gatling.core.validation._
import io.gatling.http.action.{ RequestAction, HttpRequestAction }
import io.gatling.http.ahc.HttpTx
import io.gatling.http.cache.CacheHandling
import io.gatling.http.config.HttpProtocol
import io.gatling.http.response._

sealed trait ResourceFetched {
  def uri: UriComponents
  def status: Status
  def sessionUpdates: Session => Session
}
case class RegularResourceFetched(uri: UriComponents, status: Status, sessionUpdates: Session => Session) extends ResourceFetched
case class CssResourceFetched(uri: UriComponents, status: Status, sessionUpdates: Session => Session, statusCode: Option[Int], lastModifiedOrEtag: Option[String], content: String) extends ResourceFetched

case class InferredPageResources(expire: String, requests: List[HttpRequest])

object ResourceFetcher extends StrictLogging {

  // FIXME should CssContentCache use the same key?
  case class InferredResourcesCacheKey(protocol: HttpProtocol, uri: UriComponents)
  val CssContentCache = CacheHelper.newCache[UriComponents, List[EmbeddedResource]](configuration.http.fetchedCssCacheMaxCapacity)
  val InferredResourcesCache = CacheHelper.newCache[InferredResourcesCacheKey, InferredPageResources](configuration.http.fetchedHtmlCacheMaxCapacity)

  private def applyResourceFilters(resources: List[EmbeddedResource], filters: Option[Filters]): List[EmbeddedResource] =
    filters match {
      case Some(f) => f.filter(resources)
      case none    => resources
    }

  private def resourcesToRequests(resources: List[EmbeddedResource], protocol: HttpProtocol, throttled: Boolean): List[HttpRequest] =
    resources.flatMap {
      _.toRequest(protocol, throttled) match {
        case Success(httpRequest) => Some(httpRequest)
        case Failure(m) =>
          // shouldn't happen, only static values
          logger.error("Could build request for embedded resource: " + m)
          None
      }
    }

  private def inferPageResources(request: Request, response: Response, config: HttpRequestConfig): List[HttpRequest] = {

    val htmlDocumentURI = response.request.getURI
    val protocol = config.protocol

      def inferredResourcesRequests(): List[HttpRequest] = {
        val inferred = new HtmlParser().getEmbeddedResources(htmlDocumentURI, response.body.string.unsafeChars, UserAgent.getAgent(request))
        val filtered = applyResourceFilters(inferred, protocol.responsePart.htmlResourcesInferringFilters)
        resourcesToRequests(filtered, protocol, config.throttled)
      }

    response.statusCode match {
      case Some(200) =>
        response.lastModifiedOrEtag(protocol) match {
          case Some(newLastModifiedOrEtag) =>
            val cacheKey = InferredResourcesCacheKey(protocol, htmlDocumentURI)
            InferredResourcesCache.get(cacheKey) match {
              case Some(InferredPageResources(`newLastModifiedOrEtag`, res)) =>
                //cache entry didn't expire, use it
                res
              case _ =>
                // cache entry missing or expired, update it
                val inferredResources = inferredResourcesRequests()
                // FIXME add throttle to cache key?
                InferredResourcesCache.put(cacheKey, InferredPageResources(newLastModifiedOrEtag, inferredResources))
                inferredResources
            }

          case None =>
            // don't cache
            inferredResourcesRequests()
        }

      case Some(304) =>
        // no content, retrieve from cache if exist
        InferredResourcesCache.get(InferredResourcesCacheKey(protocol, htmlDocumentURI)) match {
          case Some(inferredPageResources) => inferredPageResources.requests
          case _ =>
            logger.warn(s"Got a 304 for $htmlDocumentURI but could find cache entry?!")
            Nil
        }

      case _ => Nil
    }
  }

  private def buildExplicitResources(resources: List[HttpRequestDef], session: Session): List[HttpRequest] = resources.flatMap { resource =>

    resource.requestName(session) match {
      case Success(requestName) => resource.build(requestName, session) match {
        case Success(httpRequest) =>
          Some(httpRequest)

        case Failure(m) =>
          RequestAction.reportUnbuildableRequest(requestName, session, m)
          None
      }

      case Failure(m) =>
        logger.error("Could build request name for explicitResource: " + m)
        None
    }
  }

  private def resourceFetcher(tx: HttpTx, inferredResources: List[HttpRequest], explicitResources: List[HttpRequest]) = {

    // explicit resources have precedence over implicit ones, so add them last to the Map
    val uniqueResources: Map[UriComponents, HttpRequest] = (inferredResources ::: explicitResources).map(res => res.ahcRequest.getURI -> res)(breakOut)

    if (uniqueResources.isEmpty)
      None
    else {
      Some(() => new ResourceFetcher(tx, uniqueResources.values.toSeq))
    }
  }

  def resourceFetcherForCachedPage(htmlDocumentURI: UriComponents, tx: HttpTx): Option[() => ResourceFetcher] = {

    val inferredResources =
      InferredResourcesCache.get(InferredResourcesCacheKey(tx.request.config.protocol, htmlDocumentURI)) match {
        case None            => Nil
        case Some(resources) => resources.requests
      }

    val explicitResources = buildExplicitResources(tx.request.config.explicitResources, tx.session)

    resourceFetcher(tx, inferredResources, explicitResources)
  }

  def resourceFetcherForFetchedPage(request: Request, response: Response, tx: HttpTx, session: Session): Option[() => ResourceFetcher] = {

    val protocol = tx.request.config.protocol

    val explicitResources =
      if (tx.request.config.explicitResources.nonEmpty)
        ResourceFetcher.buildExplicitResources(tx.request.config.explicitResources, session)
      else
        Nil

    val inferredResources =
      if (protocol.responsePart.inferHtmlResources && response.isReceived && isHtml(response.headers))
        ResourceFetcher.inferPageResources(request, response, tx.request.config)
      else
        Nil

    resourceFetcher(tx, inferredResources, explicitResources)
  }
}

// FIXME handle crash
class ResourceFetcher(primaryTx: HttpTx, initialResources: Seq[HttpRequest]) extends BaseActor {

  import ResourceFetcher._

  // immutable state
  val protocol = primaryTx.request.config.protocol
  val throttled = primaryTx.request.config.throttled
  val filters = protocol.responsePart.htmlResourcesInferringFilters

  // mutable state
  var session = primaryTx.session
  val alreadySeen = mutable.Set.empty[UriComponents]
  val bufferedResourcesByHost = mutable.HashMap.empty[String, List[HttpRequest]].withDefaultValue(Nil)
  val availableTokensByHost = mutable.HashMap.empty[String, Int].withDefaultValue(protocol.enginePart.maxConnectionsPerHost)
  var pendingResourcesCount = 0
  var okCount = 0
  var koCount = 0
  val start = nowMillis

  // start fetching
  fetchOrBufferResources(initialResources)

  private def fetchResource(resource: HttpRequest): Unit = {
    logger.debug(s"Fetching resource ${resource.ahcRequest.getURI}")

    val resourceTx = primaryTx.copy(
      session = this.session,
      request = resource,
      responseBuilderFactory = ResponseBuilder.newResponseBuilderFactory(resource.config.checks, None, protocol),
      next = self,
      primary = false)

    HttpRequestAction.startHttpTransaction(resourceTx)
  }

  private def handleCachedResource(resource: HttpRequest): Unit = {

    val uri = resource.ahcRequest.getURI

    logger.info(s"Fetching resource $uri from cache")
    // FIXME check if it's a css this way or use the Content-Type?
    val resourceFetched =
      if (CssContentCache.contains(uri))
        CssResourceFetched(uri, OK, identity, None, None, "")
      else
        RegularResourceFetched(uri, OK, identity)

    // mock like we've received the resource
    receive(resourceFetched)
  }

  private def fetchOrBufferResources(resources: Iterable[HttpRequest]): Unit = {

      def fetchResources(host: String, resources: Iterable[HttpRequest]): Unit = {
        resources.foreach(fetchResource)
        availableTokensByHost += host -> (availableTokensByHost(host) - resources.size)
      }

      def bufferResources(host: String, resources: Iterable[HttpRequest]): Unit =
        bufferedResourcesByHost += host -> (bufferedResourcesByHost(host) ::: resources.toList)

    alreadySeen ++= resources.map(_.ahcRequest.getURI)
    pendingResourcesCount += resources.size

    val (cached, nonCached) = resources.partition { resource =>
      val uri = resource.ahcRequest.getURI
      CacheHandling.getExpire(protocol, session, uri) match {
        case None => false
        case Some(expire) if nowMillis > expire =>
          // beware, side effecting
          session = CacheHandling.clearExpire(session, uri)
          false
        case _ => true
      }
    }

    cached.foreach(handleCachedResource)

    nonCached
      .groupBy(_.ahcRequest.getURI.getHost)
      .foreach {
        case (host, res) =>
          val availableTokens = availableTokensByHost(host)
          val (immediate, buffered) = res.splitAt(availableTokens)
          fetchResources(host, immediate)
          bufferResources(host, buffered)
      }
  }

  private def done(): Unit = {
    logger.debug("All resources were fetched")
    primaryTx.next ! session.logGroupAsyncRequests(nowMillis - start, okCount, koCount)
    context.stop(self)
  }

  private def resourceFetched(uri: UriComponents, status: Status): Unit = {

      def releaseToken(host: String): Unit = {

          @tailrec
          def releaseTokenRec(bufferedResourcesForHost: List[HttpRequest]): Unit =
            bufferedResourcesForHost match {
              case Nil =>
                // nothing to send for this host for now
                availableTokensByHost += host -> (availableTokensByHost(host) + 1)

              case request :: tail =>
                bufferedResourcesByHost += host -> tail
                val requestUri = request.ahcRequest.getURI
                CacheHandling.getExpire(protocol, session, requestUri) match {
                  case None =>
                    // recycle token, fetch a buffered resource
                    fetchResource(request)

                  case Some(expire) if nowMillis > expire =>
                    // expire reached
                    session = CacheHandling.clearExpire(session, requestUri)
                    fetchResource(request)

                  case _ =>
                    handleCachedResource(request)
                    releaseTokenRec(tail)
                }
            }

        val hostBufferedResources = bufferedResourcesByHost.get(uri.getHost) match {
          case None            => Nil
          case Some(resources) => resources
        }

        releaseTokenRec(hostBufferedResources)
      }

    logger.debug(s"Resource $uri was fetched")
    pendingResourcesCount -= 1

    if (status == OK)
      okCount = okCount + 1
    else
      koCount = koCount + 1

    if (pendingResourcesCount == 0)
      done()
    else
      releaseToken(uri.getHost)
  }

  private def cssFetched(uri: UriComponents, status: Status, statusCode: Option[Int], lastModifiedOrEtag: Option[String], content: String): Unit = {

      def parseCssResources(): List[HttpRequest] = {
        val inferred = CssContentCache.getOrElseUpdate(uri, CssParser.extractResources(uri, content))
        val filtered = applyResourceFilters(inferred, filters)
        resourcesToRequests(filtered, protocol, throttled)
      }

    if (status == OK) {
      // this css might contain some resources

      val cssResources: List[HttpRequest] =
        statusCode match {
          case Some(200) =>
            lastModifiedOrEtag match {
              case Some(newLastModifiedOrEtag) =>
                // resource can be cached, try to get from cache instead of parsing again

                val cacheKey = InferredResourcesCacheKey(protocol, uri)

                InferredResourcesCache.get(cacheKey) match {
                  case Some(InferredPageResources(`newLastModifiedOrEtag`, inferredResources)) =>
                    //cache entry didn't expire, use it
                    inferredResources

                  case _ =>
                    // cache entry missing or expired, set/update it
                    CssContentCache.remove(uri)
                    val inferredResources = parseCssResources()
                    InferredResourcesCache.put(InferredResourcesCacheKey(protocol, uri), InferredPageResources(newLastModifiedOrEtag, inferredResources))
                    inferredResources
                }

              case None =>
                // don't cache
                parseCssResources()
            }

          case Some(304) =>
            // resource was already cached
            InferredResourcesCache.get(InferredResourcesCacheKey(protocol, uri)) match {
              case Some(inferredPageResources) => inferredPageResources.requests
              case _ =>
                logger.warn(s"Got a 304 for $uri but could find cache entry?!")
                Nil
            }
          case _ => Nil
        }

      val filtered = cssResources.filterNot(resource => alreadySeen.contains(resource.ahcRequest.getURI))
      fetchOrBufferResources(filtered)
    }
  }

  def receive: Receive = {
    case RegularResourceFetched(uri, status, sessionUpdates) =>
      session = sessionUpdates(session)
      resourceFetched(uri, status)

    case CssResourceFetched(uri, status, sessionUpdates, statusCode, lastModifiedOrEtag, content) =>
      session = sessionUpdates(session)
      cssFetched(uri, status, statusCode, lastModifiedOrEtag, content)
      resourceFetched(uri, status)
  }
}
