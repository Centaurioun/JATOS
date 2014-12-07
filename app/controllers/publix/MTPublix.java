package controllers.publix;

import models.ComponentModel;
import models.StudyModel;
import models.results.ComponentResult;
import models.results.StudyResult;
import models.workers.MTWorker;
import play.Logger;
import play.libs.F.Promise;
import play.mvc.Result;
import services.ErrorMessages;
import services.MTErrorMessages;
import services.PersistanceUtils;
import controllers.ControllerUtils;
import controllers.Users;
import exceptions.BadRequestPublixException;
import exceptions.ForbiddenReloadException;
import exceptions.PublixException;

/**
 * Implementation of JATOS' public API for studies that are started via MTurk.
 * 
 * @author Kristian Lange
 */
public class MTPublix extends Publix<MTWorker> implements IPublix {

	private static final String SANDBOX = "sandbox";
	private static final String TURK_SUBMIT_TO = "turkSubmitTo";
	public static final String ASSIGNMENT_ID_NOT_AVAILABLE = "ASSIGNMENT_ID_NOT_AVAILABLE";
	private static final String CLASS_NAME = MTPublix.class.getSimpleName();

	protected static final MTErrorMessages errorMessages = new MTErrorMessages();
	protected static final MTPublixUtils utils = new MTPublixUtils(
			errorMessages);

	public MTPublix() {
		super(utils);
	}

	@Override
	public Result startStudy(Long studyId) throws PublixException {
		// Get MTurk query parameters
		// Hint: Don't confuse MTurk's workerId with JATOS' workerId. They
		// aren't the same. JATOS' workerId is automatically generated
		// and MTurk's workerId is stored within the MTWorker.
		String mtWorkerId = getQueryString("workerId");
		String mtAssignmentId = getQueryString("assignmentId");
		String mtHitId = getQueryString("hitId");
		Logger.info(CLASS_NAME + ".startStudy: studyId " + studyId + ", "
				+ "Parameters from MTurk: workerId " + mtWorkerId + ", "
				+ "assignmentId " + mtAssignmentId + ", " + "hitId " + mtHitId);

		StudyModel study = utils.retrieveStudy(studyId);

		checkForMTurkPreview(studyId, mtAssignmentId);

		// Check worker
		if (mtWorkerId == null) {
			throw new BadRequestPublixException(ErrorMessages.NO_MTURK_WORKERID);
		}
		MTWorker worker = MTWorker.findByMTWorkerId(mtWorkerId);
		if (worker == null) {
			worker = PersistanceUtils.createMTWorker(mtWorkerId,
					isRequestFromMTurkSandbox());
		}
		utils.checkWorkerAllowedToDoStudy(worker, study);
		session(WORKER_ID, String.valueOf(worker.getId()));

		utils.finishAllPriorStudyResults(worker, study);
		PersistanceUtils.createStudyResult(study, worker);

		ComponentModel firstComponent = utils
				.retrieveFirstActiveComponent(study);
		return redirect(controllers.publix.routes.PublixInterceptor
				.startComponent(studyId, firstComponent.getId()));
	}

	@Override
	public Promise<Result> startComponent(Long studyId, Long componentId)
			throws PublixException {
		Logger.info(CLASS_NAME + ".startComponent: studyId " + studyId + ", "
				+ "componentId " + componentId + ", " + "workerId "
				+ session(WORKER_ID));

		MTWorker worker = utils.retrieveWorker();
		StudyModel study = utils.retrieveStudy(studyId);
		ComponentModel component = utils.retrieveComponent(study, componentId);
		StudyResult studyResult = utils.retrieveWorkersLastStudyResult(worker,
				study);
		ComponentResult componentResult = null;
		try {
			componentResult = utils.startComponent(component, studyResult);
		} catch (ForbiddenReloadException e) {
			return Promise
					.pure((Result) redirect(controllers.publix.routes.PublixInterceptor
							.finishStudy(studyId, false, e.getMessage())));
		}
		PublixUtils.setIdCookie(studyResult, componentResult, worker);
		String urlPath = StudiesAssets.getComponentUrlPath(study.getDirName(),
				component);
		String urlWithQueryStr = StudiesAssets
				.getUrlWithRequestQueryString(urlPath);
		return forwardTo(urlWithQueryStr);
	}

	@Override
	public Result startNextComponent(Long studyId) throws PublixException {
		Logger.info(CLASS_NAME + ".startNextComponent: studyId " + studyId
				+ ", " + "workerId " + session(WORKER_ID));
		MTWorker worker = utils.retrieveWorker();
		StudyModel study = utils.retrieveStudy(studyId);
		StudyResult studyResult = utils.retrieveWorkersLastStudyResult(worker,
				study);

		ComponentModel nextComponent = utils
				.retrieveNextActiveComponent(studyResult);
		if (nextComponent == null) {
			// Study has no more components -> finish it
			return redirect(controllers.publix.routes.PublixInterceptor
					.finishStudy(studyId, true, null));
		}
		String urlWithQueryString = StudiesAssets
				.getUrlWithRequestQueryString(controllers.publix.routes.PublixInterceptor
						.startComponent(studyId, nextComponent.getId()).url());
		return redirect(urlWithQueryString);
	}

	@Override
	public Result abortStudy(Long studyId, String message)
			throws PublixException {
		Logger.info(CLASS_NAME + ".abortStudy: studyId " + studyId + ", "
				+ "logged-in user email " + session(Users.COOKIE_EMAIL) + ", "
				+ "message \"" + message + "\"");
		StudyModel study = utils.retrieveStudy(studyId);
		MTWorker worker = utils.retrieveWorker();
		utils.checkWorkerAllowedToDoStudy(worker, study);

		StudyResult studyResult = utils.retrieveWorkersLastStudyResult(worker,
				study);
		if (!utils.studyDone(studyResult)) {
			utils.abortStudy(message, studyResult);
		}

		PublixUtils.discardIdCookie();
		if (ControllerUtils.isAjax()) {
			return ok();
		} else {
			return ok(views.html.publix.abort.render());
		}
	}

	@Override
	public Result finishStudy(Long studyId, Boolean successful, String errorMsg)
			throws PublixException {
		Logger.info(CLASS_NAME + ".finishStudy: studyId " + studyId + ", "
				+ "workerId " + session(WORKER_ID) + ", " + "successful "
				+ successful + ", " + "errorMsg \"" + errorMsg + "\"");
		StudyModel study = utils.retrieveStudy(studyId);
		MTWorker worker = utils.retrieveWorker();
		utils.checkWorkerAllowedToDoStudy(worker, study);

		StudyResult studyResult = utils.retrieveWorkersLastStudyResult(worker,
				study);
		String confirmationCode;
		if (!utils.studyDone(studyResult)) {
			confirmationCode = utils.finishStudy(successful, errorMsg,
					studyResult);
		} else {
			confirmationCode = studyResult.getConfirmationCode();
		}

		PublixUtils.discardIdCookie();
		if (ControllerUtils.isAjax()) {
			return ok(confirmationCode);
		} else {
			if (!successful) {
				return ok(views.html.publix.error.render(errorMsg));
			} else {
				return ok(views.html.publix.confirmationCode
						.render(confirmationCode));
			}
		}
	}

	private void checkForMTurkPreview(Long studyId, String mtAssignmentId)
			throws BadRequestPublixException {
		if (mtAssignmentId != null
				&& mtAssignmentId.equals(ASSIGNMENT_ID_NOT_AVAILABLE)) {
			// It's a preview coming from Mechanical Turk -> no previews
			throw new BadRequestPublixException(
					ErrorMessages.noPreviewAvailable(studyId));
		}
	}

	/**
	 * Returns true if the request comes from the Mechanical Turk Sandbox and
	 * false otherwise.
	 */
	private boolean isRequestFromMTurkSandbox() {
		String turkSubmitTo = request().getQueryString(TURK_SUBMIT_TO);
		if (turkSubmitTo != null && turkSubmitTo.contains(SANDBOX)) {
			return true;
		}
		return false;
	}

}
