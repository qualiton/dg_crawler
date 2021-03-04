package org.qualiton.crawler
package server.main

import java.util.concurrent.ExecutorService

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.effect.{ ConcurrentEffect, ContextShift, ExitCode, Timer }
import fs2.Stream
import fs2.concurrent.Queue

import org.http4s.client.middleware.RetryPolicy
import io.chrisdavenport.log4cats.{ Logger, SelfAwareStructuredLogger }
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import upperbound.Limiter
import upperbound.syntax.rate.upperboundSyntaxEvery

import org.qualiton.crawler.common.datasource.DataSource
import org.qualiton.crawler.common.kamon.KamonMetrics
import org.qualiton.crawler.domain.core.DiscussionEvent
import org.qualiton.crawler.flyway.FlywayUpdater
import org.qualiton.crawler.infrastructure.{ GithubStream, HealthcheckHttpServerStream, PublisherStream }
import org.qualiton.crawler.server.config.ServiceConfig

object Server {

  def fromConfig[F[_] : ConcurrentEffect : ContextShift : Timer](loadConfig: F[ServiceConfig])
    (implicit ec: ExecutionContext, es: ExecutorService): Stream[F, ExitCode] = {

    implicit val retryPolicy = RetryPolicy[F](RetryPolicy.exponentialBackoff(2.minutes, 10), RetryPolicy.defaultRetriable)

    implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger

    for {
      config <- Stream.eval(loadConfig)
      dataSource <- DataSource.stream(config.databaseConfig, ec, ec)
      _ <- Stream.eval(FlywayUpdater(dataSource))
      _ <- Stream.resource(KamonMetrics.resource(config.kamonConfig))
      discussionEventQueue <- Stream.eval(Queue.bounded[F, DiscussionEvent](100))
      githubApiRequestsRateLimiter <- Stream.resource(Limiter.start[F](4000 every 1.hour))
      gitStream = GithubStream(discussionEventQueue, dataSource, config.gitConfig, githubApiRequestsRateLimiter)
      publisherStream = PublisherStream(discussionEventQueue, config.publisherConfig)
      httpStream = HealthcheckHttpServerStream(config.httpPort)
      stream <- Stream(httpStream, gitStream.drain, publisherStream.drain)
        .parJoinUnbounded
        .handleErrorWith(t => Stream.eval_(Logger[F].error(t)(t.getMessage).delay))
    } yield stream
  }
}
