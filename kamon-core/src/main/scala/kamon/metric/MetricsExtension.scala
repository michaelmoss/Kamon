/*
 * =========================================================================================
 * Copyright © 2013 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.metric

import akka.actor
import kamon.metric.SubscriptionsDispatcher.{ Unsubscribe, Subscribe }
import kamon.Kamon
import kamon.metric.instrument.{ InstrumentFactory, CollectionContext }
import kamon.supervisor.ModuleSupervisor

import scala.collection.concurrent.TrieMap
import akka.actor._
import kamon.util.{ FastDispatch, TriemapAtomicGetOrElseUpdate }

object Metrics extends ExtensionId[MetricsExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): MetricsExtension = super.get(system)
  def lookup(): ExtensionId[_ <: actor.Extension] = Metrics
  def createExtension(system: ExtendedActorSystem): MetricsExtension = new MetricsExtensionImpl(system)
}

case class EntityRegistration[T <: EntityRecorder](entity: Entity, recorder: T)

trait MetricsExtension extends Kamon.Extension {
  def settings: MetricsExtensionSettings
  def shouldTrack(entity: Entity): Boolean
  def shouldTrack(entityName: String, category: String): Boolean =
    shouldTrack(Entity(entityName, category))

  def register[T <: EntityRecorder](recorderFactory: EntityRecorderFactory[T], entityName: String): Option[EntityRegistration[T]]
  def register[T <: EntityRecorder](entity: Entity, recorder: T): EntityRegistration[T]
  def unregister(entity: Entity): Unit

  def find(entity: Entity): Option[EntityRecorder]
  def find(name: String, category: String): Option[EntityRecorder]

  def subscribe(filter: SubscriptionFilter, subscriber: ActorRef): Unit =
    subscribe(filter, subscriber, permanently = false)

  def subscribe(category: String, selection: String, subscriber: ActorRef, permanently: Boolean): Unit =
    subscribe(SubscriptionFilter(category, selection), subscriber, permanently)

  def subscribe(category: String, selection: String, subscriber: ActorRef): Unit =
    subscribe(SubscriptionFilter(category, selection), subscriber, permanently = false)

  def subscribe(filter: SubscriptionFilter, subscriber: ActorRef, permanently: Boolean): Unit

  def unsubscribe(subscriber: ActorRef): Unit
  def buildDefaultCollectionContext: CollectionContext
  def instrumentFactory(category: String): InstrumentFactory
}

class MetricsExtensionImpl(system: ExtendedActorSystem) extends MetricsExtension {
  import FastDispatch.Syntax

  val settings = MetricsExtensionSettings(system)

  private val _trackedEntities = TrieMap.empty[Entity, EntityRecorder]
  private val _collectionContext = buildDefaultCollectionContext
  private val _metricsCollectionDispatcher = system.dispatchers.lookup(settings.metricCollectionDispatcher)
  private lazy val _subscriptions = ModuleSupervisor.get(system).createModule("subscriptions-dispatcher",
    SubscriptionsDispatcher.props(settings.tickInterval, collectSnapshots).withDispatcher(settings.metricCollectionDispatcher))

  def shouldTrack(entity: Entity): Boolean =
    settings.entityFilters.get(entity.category).map {
      filter ⇒ filter.accept(entity.name)

    } getOrElse (settings.trackUnmatchedEntities)

  def register[T <: EntityRecorder](recorderFactory: EntityRecorderFactory[T], entityName: String): Option[EntityRegistration[T]] = {
    import TriemapAtomicGetOrElseUpdate.Syntax
    val entity = Entity(entityName, recorderFactory.category)

    if (shouldTrack(entity)) {
      val instrumentFactory = settings.instrumentFactories.get(recorderFactory.category).getOrElse(settings.defaultInstrumentFactory)
      val recorder = _trackedEntities.atomicGetOrElseUpdate(entity, recorderFactory.createRecorder(instrumentFactory), _.cleanup).asInstanceOf[T]

      Some(EntityRegistration(entity, recorder))
    } else None
  }

  def register[T <: EntityRecorder](entity: Entity, recorder: T): EntityRegistration[T] = {
    _trackedEntities.put(entity, recorder).map { oldRecorder ⇒
      oldRecorder.cleanup
    }

    EntityRegistration(entity, recorder)
  }

  def unregister(entity: Entity): Unit =
    _trackedEntities.remove(entity).map(_.cleanup)

  def find(entity: Entity): Option[EntityRecorder] =
    _trackedEntities.get(entity)

  def find(name: String, category: String): Option[EntityRecorder] =
    find(Entity(name, category))

  def subscribe(filter: SubscriptionFilter, subscriber: ActorRef, permanent: Boolean): Unit =
    _subscriptions.fastDispatch(Subscribe(filter, subscriber, permanent))(_metricsCollectionDispatcher)

  def unsubscribe(subscriber: ActorRef): Unit =
    _subscriptions.fastDispatch(Unsubscribe(subscriber))(_metricsCollectionDispatcher)

  def buildDefaultCollectionContext: CollectionContext =
    CollectionContext(settings.defaultCollectionContextBufferSize)

  def instrumentFactory(category: String): InstrumentFactory =
    settings.instrumentFactories.getOrElse(category, settings.defaultInstrumentFactory)

  /**
   *  Collect and dispatch.
   */
  private def collectSnapshots(): Map[Entity, EntitySnapshot] = {
    val builder = Map.newBuilder[Entity, EntitySnapshot]
    _trackedEntities.foreach {
      case (identity, recorder) ⇒ builder += ((identity, recorder.collect(_collectionContext)))
    }

    builder.result()
  }
}

