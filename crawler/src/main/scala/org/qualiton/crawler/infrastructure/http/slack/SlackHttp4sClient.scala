package org.qualiton.crawler.infrastructure.http.slack

import cats.effect.{ConcurrentEffect, Effect, Sync}
import enumeratum.{CirceEnum, Enum, EnumEntry}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.string.MatchesRegex
import fs2.Stream
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.MediaType.application.json
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.headers.Accept
import org.http4s.{Headers, Method, Request, Status, Uri}
import org.qualiton.crawler.common.config.SlackConfig
import org.qualiton.crawler.domain.core.Event
import org.qualiton.crawler.domain.slack.SlackClient
import org.qualiton.crawler.infrastructure.http.slack.IncomingWebhookMessageAssembler.fromDomain
import shapeless.{Witness => W}

import scala.concurrent.ExecutionContext

class SlackHttp4sClient[F[_] : Effect] private(client: Client[F], slackConfig: SlackConfig) extends SlackClient[F] with Http4sClientDsl[F] {

  import slackConfig._

  override def sendDiscussionEvent(event: Event): F[Status] = {
    val request = Request[F](
      method = Method.POST,
      uri = Uri.unsafeFromString(baseUrl).withPath(apiToken.value),
      headers = Headers(Accept(json))).withEntity(fromDomain(event).asJson)

    client.status(request)
  }
}

object SlackHttp4sClient {
  def stream[F[_] : ConcurrentEffect](slackConfig: SlackConfig)(implicit ec: ExecutionContext): Stream[F, SlackClient[F]] =
    for {
      client <- BlazeClientBuilder[F](ec).withRequestTimeout(slackConfig.requestTimeout).stream
      slackClient <- Stream.eval(Sync[F].delay(new SlackHttp4sClient[F](client, slackConfig)))
    } yield slackClient

  type ColorCode = String Refined (MatchesRegex[W.`"#[0-9](6)"`.T])

  sealed abstract class Color(val code: String) extends EnumEntry

  object Color extends Enum[Color] with CirceEnum[Color] {

    case object Good extends Color("good")

    case object Warning extends Color("warning")

    case object Danger extends Color("danger")

    case class Custom(codeCode: ColorCode) extends Color(codeCode)

    val values = findValues
  }

  final case class Field(title: String,
                         value: String,
                         short: Boolean)

  final case class Attachment(color: String,
                              author_name: String,
                              title: String,
                              title_link: String,
                              text: String,
                              fields: List[Field],
                              ts: Long)

  final case class IncomingWebhookMessage(attachments: List[Attachment])
}

