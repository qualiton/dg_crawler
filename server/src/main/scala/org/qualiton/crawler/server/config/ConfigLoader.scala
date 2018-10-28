package org.qualiton.crawler.server.config

import scala.concurrent.duration._

import ciris._
import ciris.generic._
import ciris.refined._
import ciris.syntax._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import org.qualiton.crawler.common.config
import org.qualiton.crawler.common.config.{ DatabaseConfig, GitConfig, SlackConfig }

trait ConfigLoader {
  final def loadOrThrow(): ServiceConfig =
    load().orThrow()

  protected def load(): Either[ConfigErrors, ServiceConfig]
}

object DefaultConfigLoader extends ConfigLoader {

  def load(): Either[ConfigErrors, ServiceConfig] = {

    loadConfig(
      env[NonEmptyString]("GIT_API_TOKEN"),
      env[NonEmptyString]("SLACK_TOKEN"),
      env[Option[Boolean]]("SLACK_DISABLE_PUBLISH")
    ) { (gitApiToken, slackToken, slackDisablePublish) =>
      ServiceConfig(
        gitConfig = GitConfig(
          baseUrl = "https://api.github.com",
          requestTimeout = 5.seconds,
          apiToken = config.Secret(gitApiToken)),
        slackConfig = SlackConfig(
          baseUri = "https://hooks.slack.com/services/",
          requestTimeout = 5.seconds,
          apiToken = config.Secret(slackToken),
          enableNotificationPublish = slackDisablePublish.map(!_).getOrElse(true)),
        databaseConfig =
          DatabaseConfig(
            databaseDriverName = "org.postgresql.Driver",
            connectionString = "jdbc:postgresql://localhost:5431/postgres",
            username = "postgres",
            password = config.Secret("postgres"),
            maximumPoolSize = 5))
    }
  }
}