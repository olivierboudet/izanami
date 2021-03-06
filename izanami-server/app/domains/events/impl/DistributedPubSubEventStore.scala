package domains.events.impl

import java.nio.charset.StandardCharsets

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, Subscribe}
import akka.http.scaladsl.util.FastFuture
import akka.serialization.SerializerWithStringManifest
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import cats.Applicative
import com.typesafe.config.{Config => TsConfig}
import domains.Domain.Domain
import domains.events.EventLogger._
import domains.events.EventStore
import domains.events.Events.IzanamiEvent
import env.DistributedEventsConfig
import libs.streams.CacheableQueue
import libs.logs.IzanamiLogger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.Future
import scala.util.Try

class DistributedPubSubEventStore[F[_]: Applicative](globalConfig: TsConfig,
                                                     config: DistributedEventsConfig,
                                                     lifecycle: ApplicationLifecycle)
    extends EventStore[F] {

  logger.info(s"Starting akka cluster with config ${globalConfig.getConfig("cluster")}")

  private val actorSystemName: String =
    globalConfig.getString("cluster.system-name")
  implicit private val s =
    ActorSystem(actorSystemName, globalConfig.getConfig("cluster"))
  implicit private val mat = ActorMaterializer()

  logger.info(s"Creating distributed event store")

  private val queue = CacheableQueue[IzanamiEvent](500, queueBufferSize = 500)

  private val actor =
    s.actorOf(DistributedEventsPublisherActor.props(queue, config))

  override def publish(event: IzanamiEvent): F[Done] = {
    actor ! DistributedEventsPublisherActor.Publish(event)
    s.eventStream.publish(event)
    Applicative[F].pure(Done)
  }

  override def events(domains: Seq[Domain],
                      patterns: Seq[String],
                      lastEventId: Option[Long]): Source[IzanamiEvent, NotUsed] =
    lastEventId match {
      case Some(_) =>
        queue.sourceWithCache
          .via(dropUntilLastId(lastEventId))
          .filter(eventMatch(patterns, domains))
      case None =>
        queue.rawSource
          .filter(eventMatch(patterns, domains))
    }

  override def close() = actor ! PoisonPill

  lifecycle.addStopHook { () =>
    IzanamiLogger.info(s"Stopping actor system $actorSystemName")
    s.terminate()
  }

  override def check(): F[Unit] = Applicative[F].pure(())
}

class CustomSerializer extends SerializerWithStringManifest {
  private val UTF_8 = StandardCharsets.UTF_8.name()

  private val MessageManifest = "MessageManifest"

  def manifest(obj: AnyRef): String =
    obj match {
      case _: DistributedEventsPublisherActor.Message => MessageManifest
    }

  def identifier = 1000

  def toBinary(obj: AnyRef): Array[Byte] =
    obj match {
      case DistributedEventsPublisherActor.Message(json) =>
        Json.stringify(json).getBytes(UTF_8)
      case other =>
        throw new IllegalStateException(s"MessageSerializer : Unknow object $other")
    }

  def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    manifest match {
      case MessageManifest =>
        DistributedEventsPublisherActor.Message(Json.parse(bytes))
    }
}

object DistributedEventsPublisherActor {

  case class Publish(event: IzanamiEvent)

  case class Message(event: JsValue)

  def props(queue: CacheableQueue[IzanamiEvent], config: DistributedEventsConfig): Props =
    Props(new DistributedEventsPublisherActor(queue, config))
}

private[events] class DistributedEventsPublisherActor(queue: CacheableQueue[IzanamiEvent],
                                                      config: DistributedEventsConfig)
    extends Actor {

  import context.dispatcher

  private val mediator = DistributedPubSub(context.system).mediator
  mediator ! Subscribe(config.topic, self)

  override def receive = {
    case DistributedEventsPublisherActor.Publish(event) =>
      mediator ! Publish(config.topic, DistributedEventsPublisherActor.Message(Json.toJson(event)))
    case DistributedEventsPublisherActor.Message(json) =>
      logger.debug(s"New event $json")
      json
        .validate[IzanamiEvent]
        .fold(
          err => logger.error(s"Error deserializing event of type ${json \ "type"} : $err"),
          e => queue.offer(e)
        )
  }

  override def preStart(): Unit =
    queue
      .watchCompletion()
      .onComplete(_ => Try(context.system.eventStream.unsubscribe(self)))

  override def postStop(): Unit =
    queue.complete()
}
