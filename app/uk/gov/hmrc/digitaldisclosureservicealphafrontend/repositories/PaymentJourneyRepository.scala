/*
 * Copyright 2026 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.repositories

import com.google.inject.ImplementedBy
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes, ReplaceOptions}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.PaymentJourney
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[MongoPaymentJourneyRepository])
trait PaymentJourneyRepository:
  def upsert(journey: PaymentJourney): Future[Unit]
  def get(id: String): Future[Option[PaymentJourney]]

@Singleton
class MongoPaymentJourneyRepository @Inject()(
  mongoComponent: MongoComponent
)(using ec: ExecutionContext)
  extends PlayMongoRepository[PaymentJourney](
    collectionName = "payment-journeys",
    mongoComponent = mongoComponent,
    domainFormat   = PaymentJourney.format,
    indexes        = Seq(
      IndexModel(
        Indexes.ascending("id"),
        IndexOptions().name("idIdx").unique(true)
      ),
      IndexModel(
        Indexes.ascending("createdAt"),
        IndexOptions().name("ttlIdx").expireAfter(3600, TimeUnit.SECONDS)
      )
    ),
    replaceIndexes = true
  )
  with PaymentJourneyRepository:

  def upsert(journey: PaymentJourney): Future[Unit] =
    collection
      .replaceOne(
        Filters.equal("id", journey.id),
        journey,
        ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

  def get(id: String): Future[Option[PaymentJourney]] =
    collection
      .find(Filters.equal("id", id))
      .headOption()
