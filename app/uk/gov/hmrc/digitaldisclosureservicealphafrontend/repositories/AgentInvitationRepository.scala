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
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes, ReplaceOptions, Updates}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.AgentInvitation
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[MongoAgentInvitationRepository])
trait AgentInvitationRepository:
  def upsert(invitation: AgentInvitation): Future[Unit]
  def findByToken(token: String): Future[Option[AgentInvitation]]
  def updateStatus(token: String, status: String): Future[Unit]

@Singleton
class MongoAgentInvitationRepository @Inject()(
  mongoComponent: MongoComponent
)(using ec: ExecutionContext)
  extends PlayMongoRepository[AgentInvitation](
    collectionName = "agent-poc-invitations",
    mongoComponent = mongoComponent,
    domainFormat   = AgentInvitation.format,
    indexes        = Seq(
      IndexModel(
        Indexes.ascending("token"),
        IndexOptions().name("tokenIdx").unique(true)
      ),
      IndexModel(
        Indexes.ascending("createdAt"),
        IndexOptions().name("ttlIdx").expireAfter(24, TimeUnit.HOURS)
      )
    ),
    replaceIndexes = true
  )
  with AgentInvitationRepository:

  override def upsert(invitation: AgentInvitation): Future[Unit] =
    collection
      .replaceOne(
        Filters.equal("token", invitation.token),
        invitation,
        ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

  override def findByToken(token: String): Future[Option[AgentInvitation]] =
    collection
      .find(Filters.equal("token", token))
      .headOption()

  override def updateStatus(token: String, status: String): Future[Unit] =
    collection
      .updateOne(
        Filters.equal("token", token),
        Updates.set("status", status)
      )
      .toFuture()
      .map(_ => ())
