package org.qualiton.crawler
package application

import cats.effect.Effect
import cats.instances.option._
import cats.syntax.traverse._
import fs2.Stream
import fs2.concurrent.Queue

import io.chrisdavenport.log4cats.Logger

import org.qualiton.crawler.domain.core.DiscussionEvent
import org.qualiton.crawler.domain.git.{ EventGenerator, GithubApiClient, GithubRepository }

class GithubDiscussionHandler[F[_] : Effect : Logger] private(
    eventQueue: Queue[F, DiscussionEvent],
    githubClient: GithubApiClient[F],
    githubRepository: GithubRepository[F]) {

  def synchronizeDiscussions(): Stream[F, Unit] =
    for {
      lastUpdatedAt <- githubRepository.findLastUpdatedAt.stream
      _ <- Stream.eval(Logger[F].info(s"Synchronizing discussions updated after $lastUpdatedAt"))
      currentDiscussion <- githubClient.getTeamDiscussionsUpdatedAfter(lastUpdatedAt)
      maybePreviousDiscussion <- githubRepository.find(currentDiscussion.id).stream
      maybeEvent <- EventGenerator.generateEvent(maybePreviousDiscussion, currentDiscussion).stream
      _ <- githubRepository.save(currentDiscussion).stream
      _ <- maybeEvent.traverse(eventQueue.enqueue1).stream
    } yield ()
}

object GithubDiscussionHandler {

  def stream[F[_] : Effect : Logger](
      eventQueue: Queue[F, DiscussionEvent],
      githubClient: GithubApiClient[F],
      githubRepository: GithubRepository[F]): Stream[F, GithubDiscussionHandler[F]] =
    new GithubDiscussionHandler(eventQueue, githubClient, githubRepository).delay[F].stream

}
