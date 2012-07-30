/*
 * Copyright 2012 McEvoy Software Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.milton.http.http11;

import io.milton.http.Handler;
import io.milton.resource.ReplaceableResource;
import io.milton.resource.PutableResource;
import io.milton.resource.CollectionResource;
import io.milton.resource.Resource;
import io.milton.http.HandlerHelper;
import io.milton.http.HttpManager;
import io.milton.resource.GetableResource;
import io.milton.http.Range;
import io.milton.resource.MakeCollectionableResource;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.NotFoundException;
import io.milton.http.quota.StorageChecker.StorageErrorReason;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.milton.common.Path;
import io.milton.http.Request.Method;
import io.milton.http.Response.Status;
import io.milton.http.exceptions.ConflictException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.http.webdav.WebDavResponseHandler;
import io.milton.common.FileUtils;
import io.milton.common.RandomFileOutputStream;
import io.milton.common.LogUtils;
import io.milton.event.NewFolderEvent;
import io.milton.event.PutEvent;
import io.milton.http.Request;
import io.milton.http.Response;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;

public class PutHandler implements Handler {

	private static final Logger log = LoggerFactory.getLogger(PutHandler.class);
	private final Http11ResponseHandler responseHandler;
	private final HandlerHelper handlerHelper;
	private final PutHelper putHelper;
	private final MatchHelper matchHelper;

	public PutHandler(Http11ResponseHandler responseHandler, HandlerHelper handlerHelper, PutHelper putHelper, MatchHelper matchHelper) {
		this.responseHandler = responseHandler;
		this.handlerHelper = handlerHelper;
		this.putHelper = putHelper;
		this.matchHelper = matchHelper;
		checkResponseHandler();
	}

	private void checkResponseHandler() {
		if (!(responseHandler instanceof WebDavResponseHandler)) {
			log.warn("response handler is not a WebDavResponseHandler, so locking and quota checking will not be enabled");
		}
	}

	@Override
	public String[] getMethods() {
		return new String[]{Method.PUT.code};
	}

	@Override
	public boolean isCompatible(Resource handler) {
		return (handler instanceof PutableResource);
	}

	@Override
	public void process(HttpManager manager, Request request, Response response) throws NotAuthorizedException, ConflictException, BadRequestException, NotFoundException {
		if (!handlerHelper.checkExpects(responseHandler, request, response)) {
			return;
		}

		String host = request.getHostHeader();
		String urlToCreateOrUpdate = HttpManager.decodeUrl(request.getAbsolutePath());

		LogUtils.debug(log, "PUT request. Host:", host, " Url:", urlToCreateOrUpdate, " content length header:", request.getContentLengthHeader());

		Path path = Path.path(urlToCreateOrUpdate);
		urlToCreateOrUpdate = path.toString();

		Resource existingResource = manager.getResourceFactory().getResource(host, urlToCreateOrUpdate);
		ReplaceableResource replacee;

		StorageErrorReason storageErr = null;
		if (existingResource != null) {
			//Make sure the parent collection is not locked by someone else
			if (handlerHelper.isLockedOut(request, existingResource)) {
				log.warn("resource is locked, but not by the current user");
				respondLocked(request, response, existingResource);
				return;
			}
			// Check if the resource has been modified based on etags
			if( !matchHelper.checkIfMatch(existingResource, request)) {
				log.info("if-match comparison failed, aborting PUT request");
				responseHandler.respondPreconditionFailed(request, response, existingResource); 
				return ;
			}			
			if( matchHelper.checkIfNoneMatch(existingResource, request)) {
				log.info("if-none-match comparison failed, aborting PUT request");
				responseHandler.respondPreconditionFailed(request, response, existingResource);
				return ;
			}
			
			Resource parent = manager.getResourceFactory().getResource(host, path.getParent().toString());
			if (parent instanceof CollectionResource) {
				CollectionResource parentCol = (CollectionResource) parent;
				storageErr = handlerHelper.checkStorageOnReplace(request, parentCol, existingResource, host);
			} else {
				log.warn("parent exists but is not a collection resource: " + path.getParent());
			}
		} else {
			if( !matchHelper.checkIfMatch(null, request)) {
				log.info("if-match comparison failed on null resource, aborting PUT request");
				responseHandler.respondPreconditionFailed(request, response, existingResource);
				return ;
			}
			if( matchHelper.checkIfNoneMatch(null, request)) {
				log.info("if-none-match comparison failed on null resource, aborting PUT request");
				responseHandler.respondPreconditionFailed(request, response, existingResource);
				return ;
			}
			
			CollectionResource parentCol = putHelper.findNearestParent(manager, host, path);
			storageErr = handlerHelper.checkStorageOnAdd(request, parentCol, path.getParent(), host);
		}

		if (storageErr != null) {
			respondInsufficientStorage(request, response, storageErr);
			return;
		}

		if (existingResource != null && existingResource instanceof ReplaceableResource) {
			replacee = (ReplaceableResource) existingResource;
		} else {
			replacee = null;
		}

		if (replacee != null) {
			if (log.isTraceEnabled()) {
				log.trace("replacing content in: " + replacee.getName() + " - " + replacee.getClass());
			}
			long t = System.currentTimeMillis();
			try {
				manager.onProcessResourceStart(request, response, replacee);
				processReplace(manager, request, response, replacee);
				manager.getEventManager().fireEvent(new PutEvent(replacee));
			} finally {
				t = System.currentTimeMillis() - t;
				manager.onProcessResourceFinish(request, response, replacee, t);
			}
		} else {
			// either no existing resource, or its not replaceable. check for folder
			String nameToCreate = path.getName();
			CollectionResource folderResource = findOrCreateFolders(manager, host, path.getParent());
			if (folderResource != null) {
				long t = System.currentTimeMillis();
				try {
					if (folderResource instanceof PutableResource) {

						//Make sure the parent collection is not locked by someone else
						if (handlerHelper.isLockedOut(request, folderResource)) {
							respondLocked(request, response, folderResource);
							return;
						}

						PutableResource putableResource = (PutableResource) folderResource;
						processCreate(manager, request, response, putableResource, nameToCreate);
					} else {
						LogUtils.debug(log, "method not implemented: PUT on class: ", folderResource.getClass(), folderResource.getName());
						manager.getResponseHandler().respondMethodNotImplemented(folderResource, response, request);
					}
				} finally {
					t = System.currentTimeMillis() - t;
					manager.onProcessResourceFinish(request, response, folderResource, t);
				}
			} else {
				responseHandler.respondNotFound(response, request);
			}
		}
	}

	private void processCreate(HttpManager manager, Request request, Response response, PutableResource folder, String newName) throws ConflictException, BadRequestException, NotAuthorizedException {
		if (!handlerHelper.checkAuthorisation(manager, folder, request)) {
			responseHandler.respondUnauthorised(folder, response, request);
			return;
		}

		LogUtils.debug(log, "process: putting to: ", folder.getName());
		try {
			Long l = putHelper.getContentLength(request);
			String ct = putHelper.findContentTypes(request, newName);
			LogUtils.debug(log, "PutHandler: creating resource of type: ", ct);
			Resource newlyCreated = folder.createNew(newName, request.getInputStream(), l, ct);
			if (newlyCreated != null) {
				if (newName != null && !newName.equals(newlyCreated.getName())) {
					log.warn("getName on the created resource does not match the name requested by the client! requested: " + newName + " - created: " + newlyCreated.getName());
				}
				manager.getEventManager().fireEvent(new PutEvent(newlyCreated));
				manager.getResponseHandler().respondCreated(newlyCreated, response, request);
			} else {
				throw new RuntimeException("createNew method on: " + folder.getClass() + " returned a null resource. Must return a reference to the newly created or modified resource");
			}			
		} catch (IOException ex) {
			throw new RuntimeException("IOException reading input stream. Probably interrupted upload", ex);
		}		
	}

	private CollectionResource findOrCreateFolders(HttpManager manager, String host, Path path) throws NotAuthorizedException, ConflictException, BadRequestException {
		if (path == null) {
			return null;
		}

		Resource thisResource = manager.getResourceFactory().getResource(host, path.toString());
		if (thisResource != null) {
			// Defensive programming test for a common problem where resource factories
			// return the wrong resource for a given path
			if (thisResource.getName() != null && !thisResource.getName().equals(path.getName())) {
				log.warn("Your resource factory returned a resource with a different name to that requested!!! Requested: " + path.getName() + " returned: " + thisResource.getName() + " - resource factory: " + manager.getResourceFactory().getClass());
			}
			if (thisResource instanceof CollectionResource) {
				return (CollectionResource) thisResource;
			} else {
				log.warn("parent is not a collection: " + path);
				return null;
			}
		}

		CollectionResource parent = findOrCreateFolders(manager, host, path.getParent());
		if (parent == null) {
			log.warn("couldnt find parent: " + path);
			return null;
		}

		Resource r = parent.child(path.getName());

		if (r == null) {
			if (parent instanceof MakeCollectionableResource) {
				MakeCollectionableResource mkcol = (MakeCollectionableResource) parent;
				LogUtils.debug(log, "autocreating new folder: ", path.getName());
				CollectionResource newCol = mkcol.createCollection(path.getName());
				manager.getEventManager().fireEvent(new NewFolderEvent(newCol));
				return newCol;
			} else {
				log.debug("parent folder isnt a MakeCollectionableResource: " + parent.getName());
				return null;
			}
		} else if (r instanceof CollectionResource) {
			return (CollectionResource) r;
		} else {
			log.debug("parent in URL is not a collection: " + r.getName());
			return null;
		}
	}

	/**
	 * "If an existing resource is modified, either the 200 (OK) or 204 (No Content) response codes SHOULD be sent to indicate successful completion of the request."
	 * 
	 * @param request
	 * @param response
	 * @param replacee
	 */
	private void processReplace(HttpManager manager, Request request, Response response, ReplaceableResource replacee) throws BadRequestException, NotAuthorizedException, ConflictException, NotFoundException {
		if (!handlerHelper.checkAuthorisation(manager, replacee, request)) {
			responseHandler.respondUnauthorised(replacee, response, request);
			return;
		}
		try {
			Range range = putHelper.parseContentRange(replacee, request);
			if (range != null) {
				log.debug("partial put: " + range);
				if (replacee instanceof PartialllyUpdateableResource) {
					log.debug("doing partial put on a PartialllyUpdateableResource");
					PartialllyUpdateableResource partialllyUpdateableResource = (PartialllyUpdateableResource) replacee;
					partialllyUpdateableResource.replacePartialContent(range, request.getInputStream());
				} else if (replacee instanceof GetableResource) {
					log.debug("doing partial put on a GetableResource");
					File tempFile = File.createTempFile("milton-partial", null);
					RandomAccessFile randomAccessFile = null;

					// The new length of the resource
					long length;
					try {
						randomAccessFile = new RandomAccessFile(tempFile, "rw");
						RandomFileOutputStream tempOut = new RandomFileOutputStream(tempFile);
						GetableResource gr = (GetableResource) replacee;
						// Update the content with the supplied partial content, and get the result as an inputstream
						gr.sendContent(tempOut, null, null, null);

						// Calculate new length, if the partial put is extending it
						length = randomAccessFile.length();
						if (range.getFinish() + 1 > length) {
							length = range.getFinish() + 1;
						}

						randomAccessFile.setLength(length);
						randomAccessFile.seek(range.getStart());

						int numBytesRead;
						byte[] copyBuffer = new byte[1024];
						InputStream newContent = request.getInputStream();

						while ((numBytesRead = newContent.read(copyBuffer)) != -1) {
							randomAccessFile.write(copyBuffer, 0, numBytesRead);
						}
					} finally {
						FileUtils.close(randomAccessFile);
					}

					InputStream updatedContent = new FileInputStream(tempFile);
					BufferedInputStream bufin = new BufferedInputStream(updatedContent);

					// Now, finally, we can just do a normal update
					replacee.replaceContent(bufin, length);
				} else {
					throw new BadRequestException(replacee, "Cant apply partial update. Resource does not support PartialllyUpdateableResource or GetableResource");
				}
			} else {
				// Not a partial update, but resource implements Replaceable, so give it the new data
				Long l = request.getContentLengthHeader();
				replacee.replaceContent(request.getInputStream(), l);
			}
		} catch (IOException ex) {
			log.warn("IOException reading input stream. Probably interrupted upload: " + ex.getMessage());
			return;
		}
		// Respond with a 204
		responseHandler.respondNoContent(replacee, response, request);

		log.debug("process: finished");
	}

	public void processExistingResource(HttpManager manager, Request request, Response response, Resource resource) throws NotAuthorizedException, BadRequestException, ConflictException, NotFoundException {
		String host = request.getHostHeader();
		String urlToCreateOrUpdate = HttpManager.decodeUrl(request.getAbsolutePath());
		log.debug("process request: host: " + host + " url: " + urlToCreateOrUpdate);

		Path path = Path.path(urlToCreateOrUpdate);
		urlToCreateOrUpdate = path.toString();

		Resource existingResource = manager.getResourceFactory().getResource(host, urlToCreateOrUpdate);
		ReplaceableResource replacee;

		if (existingResource != null) {
			//Make sure the parent collection is not locked by someone else
			if (handlerHelper.isLockedOut(request, existingResource)) {
				log.warn("resource is locked, but not by the current user");
				response.setStatus(Status.SC_LOCKED); //423
				return;
			}

		}
		if (existingResource != null && existingResource instanceof ReplaceableResource) {
			replacee = (ReplaceableResource) existingResource;
		} else {
			replacee = null;
		}

		if (replacee != null) {
			processReplace(manager, request, response, (ReplaceableResource) existingResource);
		} else {
			// either no existing resource, or its not replaceable. check for folder
			String urlFolder = path.getParent().toString();
			String nameToCreate = path.getName();
			CollectionResource folderResource = findOrCreateFolders(manager, host, path.getParent());
			if (folderResource != null) {
				if (log.isDebugEnabled()) {
					log.debug("found folder: " + urlFolder + " - " + folderResource.getClass());
				}
				if (folderResource instanceof PutableResource) {

					//Make sure the parent collection is not locked by someone else
					if (handlerHelper.isLockedOut(request, folderResource)) {
						response.setStatus(Status.SC_LOCKED); //423
						return;
					}

					PutableResource putableResource = (PutableResource) folderResource;
					processCreate(manager, request, response, putableResource, nameToCreate);
				} else {
					responseHandler.respondMethodNotImplemented(folderResource, response, request);
				}
			} else {
				responseHandler.respondNotFound(response, request);
			}
		}

	}

	private void respondLocked(Request request, Response response, Resource existingResource) {
		if (responseHandler instanceof WebDavResponseHandler) {
			WebDavResponseHandler rh = (WebDavResponseHandler) responseHandler;
			rh.respondLocked(request, response, existingResource);
		} else {
			response.setStatus(Status.SC_LOCKED); //423
		}
	}

	private void respondInsufficientStorage(Request request, Response response, StorageErrorReason storageErrorReason) {
		if (responseHandler instanceof WebDavResponseHandler) {
			WebDavResponseHandler rh = (WebDavResponseHandler) responseHandler;
			rh.respondInsufficientStorage(request, response, storageErrorReason);
		} else {
			response.setStatus(Status.SC_INSUFFICIENT_STORAGE);
		}
	}
}
