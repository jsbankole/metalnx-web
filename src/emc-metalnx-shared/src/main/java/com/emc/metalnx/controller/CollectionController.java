/*
 * Copyright (c) 2015-2017, Dell EMC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.emc.metalnx.controller;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.List;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;

import org.irods.jargon.core.exception.JargonException;
import org.irods.jargon.core.utils.CollectionAndPath;
import org.irods.jargon.core.utils.MiscIRODSUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.context.WebApplicationContext;

import com.emc.metalnx.controller.utils.LoggedUserUtils;
import com.emc.metalnx.core.domain.entity.DataGridUser;
import com.emc.metalnx.core.domain.exceptions.DataGridException;
import com.emc.metalnx.services.interfaces.CollectionService;
import com.emc.metalnx.services.interfaces.ConfigService;
import com.emc.metalnx.services.interfaces.FavoritesService;
import com.emc.metalnx.services.interfaces.GroupBookmarkService;
import com.emc.metalnx.services.interfaces.GroupService;
import com.emc.metalnx.services.interfaces.HeaderService;
import com.emc.metalnx.services.interfaces.IRODSServices;
import com.emc.metalnx.services.interfaces.MetadataService;
import com.emc.metalnx.services.interfaces.PermissionsService;
import com.emc.metalnx.services.interfaces.ResourceService;
import com.emc.metalnx.services.interfaces.RuleDeploymentService;
import com.emc.metalnx.services.interfaces.UserBookmarkService;
import com.emc.metalnx.services.interfaces.UserService;

@Controller
@Scope(WebApplicationContext.SCOPE_SESSION)
@SessionAttributes({ "sourcePaths" })
@RequestMapping(value = "/collections")
public class CollectionController {

	@Autowired
	CollectionService cs;

	@Autowired
	ResourceService resourceService;

	@Autowired
	UserService userService;

	@Autowired
	GroupService groupService;

	@Autowired
	GroupBookmarkService groupBookmarkService;

	@Autowired
	UserBookmarkService userBookmarkService;

	@Autowired
	MetadataService metadataService;

	@Autowired
	GroupBookmarkController groupBookmarkController;

	@Autowired
	PermissionsService permissionsService;

	@Autowired
	IRODSServices irodsServices;

	@Autowired
	FavoritesService favoritesService;

	@Autowired
	LoggedUserUtils loggedUserUtils;

	@Autowired
	RuleDeploymentService ruleDeploymentService;

	@Autowired
	HeaderService headerService;

	@Autowired
	ConfigService configService;

	// parent path of the current directory in the tree view
	private String parentPath;

	// path to the current directory in the tree view
	private String currentPath;

	// Auxiliary structure to manage download, upload, copy and move operations
	private List<String> sourcePaths;

	// ui mode that will be shown when the rods user switches mode from admin to
	// user and vice-versa
	public static final String UI_USER_MODE = "user";
	public static final String UI_ADMIN_MODE = "admin";

	public static final int MAX_HISTORY_SIZE = 10;

	private boolean cameFromMetadataSearch;
	private boolean cameFromFilePropertiesSearch;
	private boolean cameFromBookmarks;

	private static final Logger logger = LoggerFactory.getLogger(CollectionController.class);

	/**
	 * Responds the collections/ request
	 *
	 * @param model
	 * @return the collection management template
	 * @throws JargonException
	 * @throws DataGridException
	 */

	@RequestMapping(method = RequestMethod.GET)
	public String indexViaUrl(final Model model, final HttpServletRequest request,
			@RequestParam("path") final Optional<String> path, @ModelAttribute("requestHeader") String requestHeader) {
		logger.info("indexViaUrl()");
		String myPath = path.orElse("");
		logger.info("dp Header requestHeader is :: " + requestHeader);
		try {

			if (myPath.isEmpty()) {
				logger.info("no path, go to home dir");
				myPath = cs.getHomeDirectyForCurrentUser();
			} else {
				logger.info("path provided...go to:{}", path);
				myPath = URLDecoder.decode(myPath); // TODO: do I need to worry about decoding, versus configure
													// in filter? - MCC
				// see
				// https://stackoverflow.com/questions/25944964/where-and-how-to-decode-pathvariable
			}

			logger.info("myPath:{}" + myPath);
			String uiMode = "";
			DataGridUser loggedUser = null;
			try {
				loggedUser = loggedUserUtils.getLoggedDataGridUser();
				uiMode = (String) request.getSession().getAttribute("uiMode");
				logger.info("loggedUser:{}", loggedUser);

				sourcePaths = MiscIRODSUtils.breakIRODSPathIntoComponents(myPath);
				CollectionAndPath collectionAndPath = MiscIRODSUtils
						.separateCollectionAndPathFromGivenAbsolutePath(myPath);
				this.parentPath = collectionAndPath.getCollectionParent();
				this.currentPath = myPath;

				if (uiMode == null || uiMode.isEmpty()) {
					boolean isUserAdmin = loggedUser != null && loggedUser.isAdmin();
					uiMode = isUserAdmin ? UI_ADMIN_MODE : UI_USER_MODE;
				}
			} catch (Exception je) {
				logger.error("exception geting user and user mode info", je);
				throw je;
			}

			/*
			 * See if it's a file or coll. A file redirects to the info page
			 *
			 */

			// add does user have access?
			// y - proceed
			// n - do proxy check
			// noproxy - show no access html view
			// proxy - do the proxy read only and redirect to that view

			if (cs.isDataObject(myPath)) {
				logger.info("redirect to info page");
				StringBuilder sb = new StringBuilder();
				sb.append("redirect:/collectionInfo?path=");
				sb.append(URLEncoder.encode(myPath));
				return sb.toString();
			}

			logger.info("is collection...continue to collection management");

			if (uiMode.equals(UI_USER_MODE)) {
				model.addAttribute("homePath", cs.getHomeDirectyForCurrentUser());
				model.addAttribute("publicPath", cs.getHomeDirectyForPublic());
			}

			model.addAttribute("uiMode", uiMode);
			model.addAttribute("currentPath", currentPath);
			model.addAttribute("encodedCurrentPath", URLEncoder.encode(currentPath));
			model.addAttribute("parentPath", parentPath);
			model.addAttribute("resources", resourceService.findAll());
			model.addAttribute("overwriteFileOption", loggedUser != null && loggedUser.isForceFileOverwriting());

		} catch (JargonException e) {

			logger.error("error establishing collection location", e);
			model.addAttribute("unexpectedError", true);
		}

		logger.info("displaying collections/collectionManagement");

		return "collections/collectionManagement";

	}

	/**
	 * Legacy index method used in other controllers, eventually factor out TODO:
	 * factor this out and make explicit via urls etc - mcc
	 *
	 * @param model
	 * @param request
	 * @return
	 */
	public String index(final Model model, final HttpServletRequest request) {
		logger.info("index()");
		try {
			sourcePaths.clear();

			if (!cs.isPathValid(currentPath)) {
				currentPath = cs.getHomeDirectyForCurrentUser();
				parentPath = currentPath;
			} else if (cs.isDataObject(currentPath)) {
				parentPath = currentPath.substring(0, currentPath.lastIndexOf("/") + 1);
			}

			DataGridUser loggedUser = loggedUserUtils.getLoggedDataGridUser();
			String uiMode = (String) request.getSession().getAttribute("uiMode");

			if (uiMode == null || uiMode.isEmpty()) {
				boolean isUserAdmin = loggedUser != null && loggedUser.isAdmin();
				uiMode = isUserAdmin ? UI_ADMIN_MODE : UI_USER_MODE;
			}

			if (uiMode.equals(UI_USER_MODE)) {
				model.addAttribute("homePath", cs.getHomeDirectyForCurrentUser());
				model.addAttribute("publicPath", cs.getHomeDirectyForPublic());
			}

			model.addAttribute("cameFromFilePropertiesSearch", cameFromFilePropertiesSearch);
			model.addAttribute("cameFromMetadataSearch", cameFromMetadataSearch);
			model.addAttribute("cameFromBookmarks", cameFromBookmarks);
			model.addAttribute("uiMode", uiMode);
			model.addAttribute("currentPath", currentPath);
			model.addAttribute("encodedCurrentPath", URLEncoder.encode(currentPath));

			model.addAttribute("parentPath", parentPath);
			model.addAttribute("resources", resourceService.findAll());
			model.addAttribute("overwriteFileOption", loggedUser != null && loggedUser.isForceFileOverwriting());

			cameFromMetadataSearch = false;
			cameFromFilePropertiesSearch = false;
			cameFromBookmarks = false;
		} catch (JargonException e) {
			logger.error("Could not respond to request for collections: {}", e);
			model.addAttribute("unexpectedError", true);
		}
		logger.info("returning to collections/collectionManagement");
		return "collections/collectionManagement";
	}

	public ResourceService getResourceService() {
		return resourceService;
	}

	public void setResourceService(ResourceService resourceService) {
		this.resourceService = resourceService;
	}

	public UserService getUserService() {
		return userService;
	}

	public void setUserService(UserService userService) {
		this.userService = userService;
	}

	public GroupService getGroupService() {
		return groupService;
	}

	public void setGroupService(GroupService groupService) {
		this.groupService = groupService;
	}

	public GroupBookmarkService getGroupBookmarkService() {
		return groupBookmarkService;
	}

	public void setGroupBookmarkService(GroupBookmarkService groupBookmarkService) {
		this.groupBookmarkService = groupBookmarkService;
	}

	public UserBookmarkService getUserBookmarkService() {
		return userBookmarkService;
	}

	public void setUserBookmarkService(UserBookmarkService userBookmarkService) {
		this.userBookmarkService = userBookmarkService;
	}

	public MetadataService getMetadataService() {
		return metadataService;
	}

	public void setMetadataService(MetadataService metadataService) {
		this.metadataService = metadataService;
	}

	public GroupBookmarkController getGroupBookmarkController() {
		return groupBookmarkController;
	}

	public void setGroupBookmarkController(GroupBookmarkController groupBookmarkController) {
		this.groupBookmarkController = groupBookmarkController;
	}

	public PermissionsService getPermissionsService() {
		return permissionsService;
	}

	public void setPermissionsService(PermissionsService permissionsService) {
		this.permissionsService = permissionsService;
	}

	public IRODSServices getIrodsServices() {
		return irodsServices;
	}

	public void setIrodsServices(IRODSServices irodsServices) {
		this.irodsServices = irodsServices;
	}

	public FavoritesService getFavoritesService() {
		return favoritesService;
	}

	public void setFavoritesService(FavoritesService favoritesService) {
		this.favoritesService = favoritesService;
	}

	public LoggedUserUtils getLoggedUserUtils() {
		return loggedUserUtils;
	}

	public void setLoggedUserUtils(LoggedUserUtils loggedUserUtils) {
		this.loggedUserUtils = loggedUserUtils;
	}

	public RuleDeploymentService getRuleDeploymentService() {
		return ruleDeploymentService;
	}

	public void setRuleDeploymentService(RuleDeploymentService ruleDeploymentService) {
		this.ruleDeploymentService = ruleDeploymentService;
	}

	public String getCurrentPath() {
		return currentPath;
	}

	public void setCurrentPath(String currentPath) {
		this.currentPath = currentPath;
	}

	public ConfigService getConfigService() {
		return configService;
	}

	public void setConfigService(ConfigService configService) {
		this.configService = configService;
	}

}
