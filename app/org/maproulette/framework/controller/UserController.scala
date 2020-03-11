// Copyright (C) 2019 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.framework.controller

import javax.inject.Inject
import org.apache.commons.lang3.StringUtils
import org.maproulette.exception.{InvalidException, NotFoundException, StatusMessage}
import org.maproulette.framework.model.{Challenge, User, UserSettings}
import org.maproulette.framework.psql.Paging
import org.maproulette.framework.service.ServiceManager
import org.maproulette.models.Task
import org.maproulette.session.SessionManager
import org.maproulette.utils.{Crypto, Utils}
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.Promise
import scala.util.{Failure, Success}

/**
  * @author cuthbertm
  */
class UserController @Inject() (
    serviceManager: ServiceManager,
    sessionManager: SessionManager,
    components: ControllerComponents,
    bodyParsers: PlayBodyParsers,
    crypto: Crypto
) extends AbstractController(components)
    with DefaultWrites {

  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val userReadWrite          = User.UserFormat
  implicit val challengeWrites        = Challenge.writes.challengeWrites
  implicit val taskWrites             = Task.TaskFormat
  implicit val userSearchResultWrites = User.searchResultWrites
  implicit val projectManagerWrites   = User.projectManagerWrites

  def deleteUser(osmId: Long, anonymize: Boolean): Action[AnyContent] = Action.async {
    implicit request =>
      implicit val requireSuperUser = true
      this.sessionManager.authenticatedRequest { implicit user =>
        if (anonymize) {
          this.serviceManager.user.anonymizeUser(osmId, user)
        }
        // delete the user
        this.serviceManager.user.deleteByOsmID(osmId, user)
        Ok(
          Json.toJson(
            StatusMessage(
              "OK",
              JsString(s"User with osm ID $osmId deleted from the database${if (anonymize) {
                " and anonymized"
              } else {
                ""
              }}")
            )
          )
        )
      }
  }

  def whoami(): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      Ok(Json.toJson(user))
    }
  }

  def getUser(userId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      if (userId == user.id || userId == user.osmProfile.id) {
        Ok(Json.toJson(User.withDecryptedAPIKey(user)(crypto)))
      } else if (user.isSuperUser) {
        this.serviceManager.user.retrieveByOSMId(userId) match {
          case Some(u) => Ok(Json.toJson(User.withDecryptedAPIKey(u)(crypto)))
          case None =>
            this.serviceManager.user.retrieveById(userId) match {
              case Some(u) => Ok(Json.toJson(User.withDecryptedAPIKey(u)(crypto)))
              case None    => throw new NotFoundException(s"No user found with id '$userId'")
            }
        }
      } else {
        throw new IllegalAccessException(
          "Only super users have access to other user account information"
        )
      }
    }
  }

  def getUserByOSMUsername(username: String): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        if (user.name == username) {
          Ok(Json.toJson(user))
        } else {
          // we don't need to check access here as the API only allows super users to make the call,
          // so if not a super user, the correct IllegalAccessException will be thrown
          this.serviceManager.user.retrieveByOSMUsername(username, user) match {
            case Some(u) => Ok(Json.toJson(u))
            case None    => throw new NotFoundException(s"No user found with OSM username '$username'")
          }
        }
      }
  }

  def getPublicUser(userId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      val target = this.serviceManager.user.retrieveByOSMId(userId) match {
        case Some(u) => u
        case None =>
          this.serviceManager.user.retrieveById(userId) match {
            case Some(u) => u
            case None    => throw new NotFoundException(s"No user found with id '$userId'")
          }
      }

      Ok(buildBasicUser(target))
    }
  }

  def getPublicUserByOSMUsername(username: String): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        this.serviceManager.user.retrieveByOSMUsername(username, User.superUser) match {
          case Some(u) => Ok(buildBasicUser(u))
          case None    => throw new NotFoundException(s"No user found with OSM username '$username'")
        }
      }
  }

  private def buildBasicUser(user: User): JsValue = {
    val avatar       = user.osmProfile.avatarURL
    val displayName  = user.osmProfile.displayName
    val leaderOptOut = user.settings.leaderboardOptOut.getOrElse(false)

    Json.obj(
      "id" -> user.id,
      "osmProfile" -> Json
        .obj("id" -> user.osmProfile.id, "avatarURL" -> avatar, "displayName" -> displayName),
      "name"     -> user.name,
      "created"  -> user.created.toString,
      "settings" -> Json.obj("leaderboardOptOut" -> leaderOptOut)
    )
  }

  def searchUserByOSMUsername(username: String, limit: Int): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        if (StringUtils.isEmpty(username)) {
          Ok(Json.toJson(List[JsValue]()))
        } else {
          Ok(Json.toJson(this.serviceManager.user.searchByOSMUsername(username, Paging(limit))))
        }
      }
  }

  def updateUser(id: Long): Action[JsValue] = Action.async(bodyParsers.json) { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      implicit val settingsRead: Reads[UserSettings] = User.settingsReads
      this.serviceManager.user.managedUpdate(
        id,
        request.body.as[UserSettings],
        (request.body \ "properties").toOption,
        user
      ) match {
        case Some(u) => Ok(Json.toJson(u))
        case None    => throw new NotFoundException(s"No user found to update with id '$id'")
      }
    }
  }

  /**
    * Action to refresh the user's OSM profile
    *
    * @return Ok Status with no content
    */
  def refreshProfile(osmUserId: Long): Action[AnyContent] = Action.async { implicit request =>
    sessionManager.authenticatedFutureRequest { implicit user =>
      val p = Promise[Result]
      this.serviceManager.user.retrieveByOSMId(osmUserId) match {
        case Some(u) =>
          sessionManager.refreshProfile(u.osmProfile.requestToken, user) onComplete {
            case Success(_) => p success Ok
            case Failure(f) => p failure f
          }
        case None =>
          p failure new NotFoundException(s"Failed to find any user with OSM User id [$osmUserId]")
      }
      p.future
    }
  }

  def getSavedChallenges(userId: Long, limit: Int, offset: Int): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        Ok(
          Json.toJson(
            this.serviceManager.user.getSavedChallenges(userId, user, Paging(limit, offset))
          )
        )
      }
  }

  def saveChallenge(userId: Long, challengeId: Long): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        this.serviceManager.user.saveChallenge(userId, challengeId, user)
        Ok(
          Json
            .toJson(StatusMessage("OK", JsString(s"Challenge $challengeId saved for user $userId")))
        )
      }
  }

  def unsaveChallenge(userId: Long, challengeId: Long): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        this.serviceManager.user.unsaveChallenge(userId, challengeId, user)
        Ok(
          Json.toJson(
            StatusMessage("OK", JsString(s"Challenge $challengeId unsaved from user $userId"))
          )
        )
      }
  }

  def getSavedTasks(
      userId: Long,
      challengeIds: String,
      limit: Int,
      offset: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val ids = challengeIds.split(",").filter(_.nonEmpty) match {
        case v if v.nonEmpty => v.map(_.toLong).toSeq
        case _               => Seq.empty[Long]
      }
      Ok(
        Json
          .toJson(this.serviceManager.user.getSavedTasks(userId, user, ids, Paging(limit, offset)))
      )
    }
  }

  def saveTask(userId: Long, taskId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.serviceManager.user.saveTask(userId, taskId, user)
      Ok(Json.toJson(StatusMessage("OK", JsString(s"Task $taskId saved for user $userId"))))
    }
  }

  def unsaveTask(userId: Long, taskId: Long): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        this.serviceManager.user.unsaveTask(userId, taskId, user)
        Ok(Json.toJson(StatusMessage("OK", JsString(s"Task $taskId unsaved from user $userId"))))
      }
  }

  /**
    * Add the user to the Admin group of a Project
    *
    * @param userId    The id of the User to add
    * @param projectId The project to add too
    * @param groupType The type of group 1 - Admin, 2 - Write, 3 - Read
    * @return Standard status message
    */
  def addUserToProject(
      userId: Long,
      projectId: Long,
      groupType: Int,
      isOSMUserId: Boolean
  ): Action[AnyContent] = Action.async { implicit request =>
    sessionManager.authenticatedRequest { implicit user =>
      this.addUser(userId, isOSMUserId, projectId, groupType, user)
      // clear group caches
      this.serviceManager.group.clearCache()
      Ok(
        Json.toJson(
          StatusMessage("OK", JsString(s"User with id [$userId] added to project $projectId"))
        )
      )
    }
  }

  private def addUser(
      userId: Long,
      isOSMUserId: Boolean,
      projectId: Long,
      groupType: Int,
      user: User
  ): Unit = {
    val addUser = this.retrieveUser(userId, isOSMUserId, user)

    if (addUser.groups.exists(g => g.projectId == projectId && g.groupType == groupType)) {
      throw new InvalidException(s"User ${addUser.name} is already part of project $projectId")
    }
    // quick verification to make sure that the project exists
    this.serviceManager.project.retrieve(projectId) match {
      case Some(_) => // just ignore
      case None    => throw new NotFoundException(s"Could not find project with ID $projectId")
    }
    this.serviceManager.user.addUserToProject(addUser.osmProfile.id, projectId, groupType, user)
  }

  private def retrieveUser(userId: Long, isOSMUserId: Boolean, user:User): User = {
    isOSMUserId match {
      case true =>
        this.serviceManager.user.retrieveByOSMId(userId) match {
          case Some(u) => u
          case None    => throw new NotFoundException(s"Could not find user with OSM ID $userId")
        }
      case false =>
        this.serviceManager.user.retrieveById(userId) match {
          case Some(u) => u
          case None    => throw new NotFoundException(s"Could not find user with ID $userId")
        }
    }
  }

  def addUsersToProject(projectId: Long, groupType: Int, isOSMUserId: Boolean): Action[JsValue] =
    Action.async(bodyParsers.json) { implicit request =>
      sessionManager.authenticatedRequest { implicit user =>
        val jsBody = request.body
        if (!jsBody.isInstanceOf[JsArray]) {
          throw new InvalidException("Expecting JSON array of user id's for this request")
        }

        val idList = jsBody.as[JsArray].value
        idList.foreach(id => {
          this.addUser(id.as[Long], isOSMUserId, projectId, groupType, user)
        })
        // clear the group caches
        this.serviceManager.group.clearCache()
        Ok(
          Json.toJson(
            StatusMessage(
              "OK",
              JsString(s"Users with ids [${idList.mkString(",")} added to project $projectId")
            )
          )
        )
      }
    }

  /**
    * Sets the group type of the user in a project, first removing any prior
    * group types.
    *
    * @param userId    The id of the User to add
    * @param projectId The project to add too
    * @param groupType The type of group 1 - Admin, 2 - Write, 3 - Read
    * @return Standard status message
    */
  def setUserProjectGroup(
      userId: Long,
      projectId: Long,
      groupType: Int,
      isOSMUserId: Boolean
  ): Action[AnyContent] = Action.async { implicit request =>
    sessionManager.authenticatedRequest { implicit user =>
      val addUser = retrieveUser(userId, isOSMUserId, user)
      this.serviceManager.user
        .addUserToProject(addUser.osmProfile.id, projectId, groupType, user, clear = true)
      // clear group caches
      this.serviceManager.user.clearCache()
      Ok(Json.toJson(this.serviceManager.user.getUsersManagingProject(projectId, None, user)))
    }
  }

  /**
    * Removes the user from the Admin group of the Project
    *
    * @param userId
    * @param projectId
    * @return
    */
  def removeUserFromProject(
      userId: Long,
      projectId: Long,
      groupType: Int,
      isOSMUserId: Boolean
  ): Action[AnyContent] = Action.async { implicit request =>
    sessionManager.authenticatedRequest { implicit user =>
      this.removeUser(userId, isOSMUserId, projectId, groupType, user)
      // clear group caches
      this.serviceManager.group.clearCache()
      Ok(
        Json.toJson(
          StatusMessage("OK", JsString(s"User with id $userId removed from project $projectId"))
        )
      )
    }
  }

  private def removeUser(
      userId: Long,
      isOSMUserId: Boolean,
      projectId: Long,
      groupType: Int,
      user: User
  ): Unit = {
    val addUser = this.retrieveUser(userId, isOSMUserId, user)
    // just check to make sure that the project exists
    this.serviceManager.project.retrieve(projectId) match {
      case Some(_) => // just ignore
      case None    => throw new NotFoundException(s"Could not find project with ID $projectId")
    }
    this.serviceManager.user
      .removeUserFromProject(addUser.osmProfile.id, projectId, groupType, user)
  }

  def removeUsersFromProject(
      projectId: Long,
      groupType: Int,
      isOSMUserId: Boolean
  ): Action[JsValue] = Action.async(bodyParsers.json) { implicit request =>
    sessionManager.authenticatedRequest { implicit user =>
      val jsBody = request.body
      if (!jsBody.isInstanceOf[JsArray]) {
        throw new InvalidException("Expecting JSON array of user id's for this request")
      }

      val idList = jsBody.as[JsArray].value
      idList.foreach(id => {
        this.removeUser(id.as[Long], isOSMUserId, projectId, groupType, user)
      })
      // clear the group caches
      this.serviceManager.group.clearCache()
      Ok(
        Json.toJson(
          StatusMessage(
            "OK",
            JsString(s"Users with ids [${idList.mkString(",")} removed from project $projectId")
          )
        )
      )
    }
  }

  /**
    * Generates a new API key for the user. A user can then use the API key to make API calls directly against
    * the server. Only the current API key for the user will work on any authenticated API calls, any previous
    * keys are immediately discarded once a new one is created.
    *
    * @return Will return NoContent if cannot create the key (which most likely means that no user was
    *         found, or will return the api key as plain text.
    */
  def generateAPIKey(userId: Long = -1): Action[AnyContent] = Action.async { implicit request =>
    sessionManager.authenticatedRequest { implicit user =>
      val newAPIUser = if (user.isSuperUser && userId != -1) {
        this.serviceManager.user.retrieveById(userId) match {
          case Some(u) => u
          case None => // look for the user under the OSM_ID
            this.serviceManager.user.retrieveByOSMId(userId) match {
              case Some(u) => u
              case None =>
                throw new NotFoundException(
                  s"No user found with id [$userId], no API key could be generated."
                )
            }
        }
      } else {
        user
      }
      this.serviceManager.user.generateAPIKey(newAPIUser, user) match {
        case Some(updated) =>
          updated.apiKey match {
            case Some(api) => Ok(api)
            case None      => NoContent
          }
        case None => NoContent
      }
    }
  }

  def getUsersManagingProject(projectId: Long, osmIds: String): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        Ok(
          Json
            .toJson(
              this.serviceManager.user
                .getUsersManagingProject(projectId, Utils.toLongList(osmIds), user)
            )
        )
      }
  }

  def getMetricsForUser(
      userId: Long,
      monthDuration: Int = -1,
      reviewDuration: Int = -1,
      reviewerDuration: Int = -1,
      start: String,
      end: String,
      reviewStart: String,
      reviewEnd: String,
      reviewerStart: String,
      reviewerEnd: String
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(
        Json.toJson(
          this.serviceManager.userMetrics.getMetricsForUser(
            userId,
            User.userOrMocked(user),
            monthDuration,
            reviewDuration,
            reviewerDuration,
            start,
            end,
            reviewStart,
            reviewEnd,
            reviewerStart,
            reviewerEnd
          )
        )
      )
    }
  }
}