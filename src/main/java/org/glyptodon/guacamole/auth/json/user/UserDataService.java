/*
 * Copyright (C) 2015 Glyptodon LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.glyptodon.guacamole.auth.json.user;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.xml.bind.DatatypeConverter;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.net.auth.Connection;
import org.apache.guacamole.net.auth.Credentials;
import org.apache.guacamole.net.auth.Directory;
import org.apache.guacamole.net.auth.User;
import org.apache.guacamole.net.auth.permission.ObjectPermissionSet;
import org.apache.guacamole.net.auth.simple.SimpleDirectory;
import org.apache.guacamole.net.auth.simple.SimpleObjectPermissionSet;
import org.apache.guacamole.net.auth.simple.SimpleUser;
import org.codehaus.jackson.map.ObjectMapper;
import org.glyptodon.guacamole.auth.json.ConfigurationService;
import org.glyptodon.guacamole.auth.json.CryptoService;
import org.glyptodon.guacamole.auth.json.RequestValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.muquit.libsodiumjna.exceptions.SodiumLibraryException;

/**
 * Service for deriving Guacamole extension API data from UserData objects.
 *
 * @author Michael Jumper
 */
@Singleton
public class UserDataService {

	/**
	 * Logger for this class.
	 */
	private final Logger logger = LoggerFactory.getLogger(UserDataService.class);

	/**
	 * ObjectMapper for deserializing UserData objects.
	 */
	private static final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Blacklist of single-use user data objects which have already been used.
	 */
	private final UserDataBlacklist blacklist = new UserDataBlacklist();

	/**
	 * Service for retrieving configuration information regarding the
	 * JSONAuthenticationProvider.
	 */
	@Inject
	private ConfigurationService confService;

	/**
	 * Service for testing the validity of HTTP requests.
	 */
	@Inject
	private RequestValidationService requestService;

	/**
	 * Service for handling cryptography-related operations.
	 */
	@Inject
	private CryptoService cryptoService;

	/**
	 * Provider for UserDataConnection instances.
	 */
	@Inject
	private Provider<UserDataConnection> userDataConnectionProvider;

	/**
	 * The name of the HTTP parameter from which base64-encoded, encrypted JSON
	 * data should be read. The value of this parameter, when decoded and
	 * decrypted, must be valid JSON prepended with the 32-byte raw binary
	 * signature generated through signing the JSON with the secret key using
	 * HMAC/SHA-256.
	 */
	public static final String ENCRYPTED_DATA_PARAMETER = "data";

	/**
	 * Derives a new UserData object from the data contained within the given
	 * Credentials. If no such data is present, or the data present is invalid,
	 * null is returned.
	 *
	 * @param credentials The Credentials from which the new UserData object
	 * should be derived.
	 *
	 * @return A new UserData object derived from the data contained within the
	 * given Credentials, or null if no such data is present or if the data
	 * present is invalid.
	 */
	public UserData fromCredentials(Credentials credentials) {

		String json;
		byte[] correctSignature;

		// Pull HTTP request, if available
		HttpServletRequest request = credentials.getRequest();
		if (request == null) {
			return null;
		}

		// Abort if the request itself is not allowed
		if (!requestService.isAuthenticationAllowed(request)) {
			return null;
		}

		// Pull base64-encoded, encrypted JSON data from HTTP request, if any
		// such data is present
		String base64 = request.getParameter(ENCRYPTED_DATA_PARAMETER);
		if (base64 == null) {
			return null;
		}

		// Decrypt base64-encoded parameter
		try {
			json = cryptoService.boxOpen(DatatypeConverter.parseBase64Binary(base64),confService.getSecretKey());
			//@todo: set correctSignature.
			//correctSignature = 
		} // Fail if base64 data is not valid
		catch (UnsupportedEncodingException e) {
			logger.error("Unexpected lack of support for UTF-8: {}", e.getMessage());
			logger.debug("Unable to decode base64 data as UTF-8.", e);
			return null;
		} // Fail if decryption or key retrieval fails for any reason
		catch (SodiumLibraryException e) {
			logger.error("Decryption of received data failed: {}", e.getMessage());
			logger.debug("Unable to decrypt received data.", e);
			return null;
		}catch (GuacamoleException e) {
			logger.error("Could not find json-secret-key in configuration: {}", e.getMessage());
			logger.debug("Missing configuration.", e);
			return null;
		}

		// Deserialize UserData from submitted JSON data
		try {

			// Deserialize UserData, but reject if expired
			UserData userData = mapper.readValue(json, UserData.class);
			if (userData.isExpired()) {
				return null;
			}

			// Reject if data is single-use and already present in the blacklist
			if (userData.isSingleUse() && !blacklist.add(userData, correctSignature)) {
				return null;
			}

			return userData;

		} // Fail UserData creation if JSON is invalid/unreadable
		catch (IOException e) {
			logger.error("Received JSON is invalid: {}", e.getMessage());
			logger.debug("Error parsing UserData JSON.", e);
			return null;
		}

	}

	/**
	 * Returns the identifiers of all users readable by the user whose data is
	 * given by the provided UserData object. As users of the
	 * JSONAuthenticationProvider can only see themselves, this will always simply
	 * be a set of the user's own username.
	 *
	 * @param userData All data associated with the user whose accessible user
	 * identifiers are being retrieved.
	 *
	 * @return A set containing the identifiers of all users readable by the user
	 * whose data is given by the provided UserData object.
	 */
	public Set<String> getUserIdentifiers(UserData userData) {

		// Each user can only see themselves
		return Collections.singleton(userData.getUsername());

	}

	/**
	 * Returns the user object of the user to whom the given UserData object
	 * belongs.
	 *
	 * @param userData All data associated with the user whose own user object is
	 * being retrieved.
	 *
	 * @return The user object of the user to whom the given UserData object
	 * belongs.
	 */
	public User getUser(UserData userData) {

		// Build user object with READ access to all available data
		return new SimpleUser(userData.getUsername()) {

			@Override
			public ObjectPermissionSet getUserPermissions() throws GuacamoleException {
				return new SimpleObjectPermissionSet(getUserIdentifiers(userData));
			}

			@Override
			public ObjectPermissionSet getConnectionPermissions() throws GuacamoleException {
				return new SimpleObjectPermissionSet(getConnectionIdentifiers(userData));
			}

			@Override
			public ObjectPermissionSet getConnectionGroupPermissions() throws GuacamoleException {
				return new SimpleObjectPermissionSet(getConnectionGroupIdentifiers(userData));
			}

		};

	}

	/**
	 * Returns the identifiers of all connections readable by the user whose data
	 * is given by the provided UserData object. If the provided UserData is not
	 * expired, this will be the set of all connection identifiers within the
	 * UserData. If the UserData is expired, this will be an empty set.
	 *
	 * @param userData All data associated with the user whose accessible
	 * connection identifiers are being retrieved.
	 *
	 * @return A set containing the identifiers of all connections readable by the
	 * user whose data is given by the provided UserData object.
	 */
	public Set<String> getConnectionIdentifiers(UserData userData) {

		// Do not return any connections if empty or expired
		Map<String, UserData.Connection> connections = userData.getConnections();
		if (connections == null || userData.isExpired()) {
			return Collections.<String>emptySet();
		}

		// Return all available connection identifiers
		return connections.keySet();

	}

	/**
	 * Returns a Directory containing all connections accessible by the user whose
	 * data is given by the provided UserData object. If the given UserData object
	 * is not expired, this Directory will contain absolutely all connections
	 * defined within the given UserData. If the given UserData object is expired,
	 * this Directory will be empty.
	 *
	 * @param userData All data associated with the user whose connection
	 * directory is being retrieved.
	 *
	 * @return A Directory containing all connections accessible by the user whose
	 * data is given by the provided UserData object.
	 */
	public Directory<Connection> getConnectionDirectory(UserData userData) {

		// Do not return any connections if empty or expired
		Map<String, UserData.Connection> connections = userData.getConnections();
		if (connections == null || userData.isExpired()) {
			return new SimpleDirectory<>();
		}

		// Convert UserData.Connection objects to normal Connections
		Map<String, Connection> directoryContents = new HashMap<>();
		for (Map.Entry<String, UserData.Connection> entry : connections.entrySet()) {

			// Pull connection and associated identifier
			String identifier = entry.getKey();
			UserData.Connection connection = entry.getValue();

			// Create Guacamole connection containing the defined identifier
			// and parameters
			Connection guacConnection = userDataConnectionProvider.get().init(
				userData,
				identifier,
				connection
			);

			// Add corresponding Connection to directory
			directoryContents.put(identifier, guacConnection);

		}

		return new SimpleDirectory<>(directoryContents);

	}

	/**
	 * Returns the identifiers of all connection groups readable by the user whose
	 * data is given by the provided UserData object. This will always be a set
	 * containing only the root connection group identifier. The
	 * JSONAuthenticationProvider does not define any other connection groups.
	 *
	 * @param userData All data associated with the user whose accessible
	 * connection group identifiers are being retrieved.
	 *
	 * @return A set containing the identifiers of all connection groups readable
	 * by the user whose data is given by the provided UserData object.
	 */
	public Set<String> getConnectionGroupIdentifiers(UserData userData) {

		// The only connection group available is the root group
		return Collections.singleton(UserContext.ROOT_CONNECTION_GROUP);

	}

}
