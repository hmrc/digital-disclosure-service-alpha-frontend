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
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.UploadJourney
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[MongoUploadJourneyRepository])
trait UploadJourneyRepository:
  def upsert(journey: UploadJourney): Future[Unit]
  def findByReference(reference: String): Future[Option[UploadJourney]]

@Singleton
class MongoUploadJourneyRepository @Inject()(
  mongoComponent: MongoComponent
)(using ec: ExecutionContext)
  extends PlayMongoRepository[UploadJourney](
    collectionName = "upload-journeys",
    mongoComponent = mongoComponent,
    domainFormat   = UploadJourney.format,
    indexes        = Seq(
      IndexModel(
        Indexes.ascending("reference"),
        IndexOptions().name("referenceIdx").unique(true)
      ),
      IndexModel(
        Indexes.ascending("createdAt"),
        IndexOptions().name("ttlIdx").expireAfter(3600, TimeUnit.SECONDS)
      )
    ),
    replaceIndexes = true
  )
  with UploadJourneyRepository:

  def upsert(journey: UploadJourney): Future[Unit] =
    collection
      .replaceOne(
        Filters.equal("reference", journey.reference),
        journey,
        ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

  def findByReference(reference: String): Future[Option[UploadJourney]] =
    collection
      .find(Filters.equal("reference", reference))
      .headOption()
