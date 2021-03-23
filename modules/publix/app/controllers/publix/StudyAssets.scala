package controllers.publix

import java.io.{File, IOException}
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import daos.common.{ComponentDao, StudyDao}
import exceptions.publix.{ForbiddenPublixException, NotFoundPublixException, PublixException}
import general.common.{Common, MessagesStrings}
import javax.inject.{Inject, Singleton}
import play.api.Logger
import play.api.mvc._
import play.core.j.JavaHelpers
import play.db.jpa.JPAApi
import services.publix.PublixErrorMessages
import services.publix.idcookie.IdCookieService
import utils.common.{Helpers, IOUtils}

import scala.compat.java8.FunctionConverters.asJavaSupplier
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.matching.Regex

/**
  * Manages web-access to files in the external study assets directories (outside of JATOS'
  * packed Jar).
  *
  * @author Kristian Lange
  */
@Singleton
class StudyAssets @Inject()(components: ControllerComponents,
                            ioUtils: IOUtils,
                            idCookieService: IdCookieService,
                            jpa: JPAApi,
                            studyDao: StudyDao,
                            assets: Assets) extends AbstractController(components) {

  private val logger: Logger = Logger(this.getClass)

  private val URL_PATH_SEPARATOR = "/"

  /**
    * Identifying part of any URL that indicates an access to the study assets directories.
    */
  val URL_STUDY_ASSETS = "study_assets"

  val jatosPublixPattern: Regex = "(.*)(jatos-publix)(.*)".r

  /**
    * Returns the study asset file that belongs to the study with the given study ID
    * and has the given relative path within the study assets folder. In difference to
    * the viaAssetsPath method it is not necessary to add the prefix 'study_assets' or the study
    * assets folder name (because it's retrieved from the DB).
    * Additionally this method can be used to get jatos.js and other javascripts from JATOS.
    */
  def viaStudyPath(studyId: Long, arbitrary: Object, urlPath: String): Action[AnyContent] =
    urlPath match {
      case "jatos.js" => assets.at(path = "/public/lib/jatos-publix/javascripts", file = "jatos.js")
      case "jatos-3.5.2.js" => assets.at(path = "/public/lib/jatos-publix/javascripts", file = "jatos-3.5.2.js")
      case jatosPublixPattern(_, _, file) => assets.at(path = "/public/lib/jatos-publix", file)
      case _ => jpa.withTransaction(asJavaSupplier(() => {
        val study = studyDao.findById(studyId)
        if (study == null) {
          BadRequest(MessagesStrings.studyNotExist(studyId))
        }
        viaAssetsPath(study.getDirName + URL_PATH_SEPARATOR + urlPath)
      }))
    }

  /**
    * Action called while routing. Translates the given file path from the URL into a file path
    * of the OS's file system and returns the file.
    */
  def viaAssetsPath(urlPath: String): Action[AnyContent] = Action { request =>
    // Set Http.Context used in Play with Java. Needed by IdCookieService
    play.mvc.Http.Context.current.set(play.core.j.JavaHelpers.createJavaContext(request, JavaHelpers.createContextComponents()))

    val urlDecodedPath = URLDecoder.decode(urlPath, StandardCharsets.UTF_8.name())
    val filePath = urlDecodedPath.replace(URL_PATH_SEPARATOR, File.separator)
    try {
      checkProperAssets(urlPath) // Windows needs URL path
      val file = ioUtils.getExistingFileSecurely(Common.getStudyAssetsRootPath, filePath)
      logger.debug(s".viaAssetsPath: loading file ${file.getPath}.")
      if (request.headers.hasHeader(RANGE)) {
        // Support range requests (needed for videos in Safari)
        // https://www.playframework.com/documentation/2.7.x/AssetsOverview#Range-requests-support
        RangeResult.ofFile(file, request.headers.get(RANGE), Option.empty)
      } else {
        Ok.sendFile(file, true).withHeaders("Cache-Control" -> "private")
      }
    } catch {
      case e: PublixException =>
        val errorMsg = e.getMessage
        logger.info(".viaAssetsPath: " + errorMsg)
        if (Helpers.isAjax) Forbidden(errorMsg)
        else Forbidden(views.html.publix.error.render(errorMsg))
      case _: IOException =>
        logger.info(s".viaAssetsPath: failed loading from path ${Common.getStudyAssetsRootPath}" +
          s"${File.separator}$filePath")
        val errorMsg = s"Resource '$filePath' couldn't be found."
        if (Helpers.isAjax) NotFound(errorMsg)
        else NotFound(views.html.publix.error.render(errorMsg))
    }
  }

  /**
    * Throws a ForbiddenPublixException if this request is not allowed to access the study assets
    * given in the URL path. It compares the study assets that are within the given filePath with
    * all study assets that are stored in the JATOS ID cookies. If at least one of them has the
    * study assets then the filePath is allowed. If not a ForbiddenPublixException is thrown.
    *
    * Drawback: It can't compare with the ID cookie that actually belongs to this study run since
    * it has no way of finding out which it is (we have no study result ID). But since all ID cookie
    * originate in the same browser one can assume this worker is allowed to access the study
    * assets.
    */
  @throws[PublixException]
  private def checkProperAssets(urlPath: String): Unit = {
    val filePathArray = urlPath.split(URL_PATH_SEPARATOR)
    if (filePathArray.isEmpty)
      throw new ForbiddenPublixException(PublixErrorMessages.studyAssetsNotAllowedOutsideRun(urlPath))
    val studyAssets = URLDecoder.decode(filePathArray(0), StandardCharsets.UTF_8.name())
    if (!idCookieService.oneIdCookieHasThisStudyAssets(studyAssets))
      throw new ForbiddenPublixException(PublixErrorMessages.studyAssetsNotAllowedOutsideRun(urlPath))
  }

  /**
    * Retrieves the component's HTML file from the study assets
    */
  @throws[NotFoundPublixException]
  def retrieveComponentHtmlFile(studyDirName: String, componentHtmlFilePath: String): Result = {
    try {
      val file = ioUtils.getFileInStudyAssetsDir(studyDirName, componentHtmlFilePath)
      Ok.sendFile(file).as("text/html; charset=utf-8")
        .withHeaders("Cache-Control" -> "no-cache, no-store")
    } catch {
      case _: IOException =>
        throw new NotFoundPublixException(
          MessagesStrings.htmlFilePathNotExist(studyDirName, componentHtmlFilePath))
    }
  }

  /**
    * Redirects to or shows the end page (either from study assets or default end page) after a study run finished.
    * Passes on the confirmationCode in case it's defined (either cookie or URL query parameter).
    */
  def endPage(studyId: Long, confirmationCode: Option[String] = None): Action[AnyContent] = Action { _ =>
    jpa.withTransaction(asJavaSupplier(() => {
      val study = studyDao.findById(studyId)

      if (study == null) BadRequest(MessagesStrings.studyNotExist(studyId))

      else if (study.getEndRedirectUrl != null && study.getEndRedirectUrl.trim() != "") {
        // Redirect to URL specified in study properties
        confirmationCode match {
          case Some(cc) => Redirect(study.getEndRedirectUrl, Map("confirmationCode" -> Seq(cc)))
          case None => Redirect(study.getEndRedirectUrl)
        }

      } else if (ioUtils.checkFileInStudyAssetsDirExists(study.getDirName, "endPage.html")) {
        // Redirect to endPage.html from study assets
        confirmationCode match {
          case Some(cc) => Ok.sendFile(ioUtils.getExistingFileInStudyAssetsDir(study.getDirName, "endPage.html"))
            .withCookies(confirmationCodeCookie(cc)).bakeCookies()
          case None => Ok.sendFile(ioUtils.getExistingFileInStudyAssetsDir(study.getDirName, "endPage.html"))
        }

      } else {
        // Return default end page
        confirmationCode match {
          case Some(cc) => Ok(views.html.publix.confirmationCode.render(cc))
          case None => Ok(views.html.publix.endPage.render())
        }
      }
    }));
  }

  private def confirmationCodeCookie(confirmationCode: String): Cookie = Cookie(
    name = "JATOS_CONFIRMATION_CODE",
    value = confirmationCode,
    maxAge = Some(86400), // 1 day
    path = Common.getPlayHttpContext,
    httpOnly = false)

}
