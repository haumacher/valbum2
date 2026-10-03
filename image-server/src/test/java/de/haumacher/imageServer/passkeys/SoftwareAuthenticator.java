/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.passkeys;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorAssertionResponse;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialDescriptor;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A browser with a passkey authenticator, in software, for the tests of issue #204: what
 * <code>navigator.credentials</code> does with the options the server answers, made by the
 * emulator webauthn4j publishes (<code>webauthn4j-test</code>), and answered in the JSON form a
 * page sends back.
 */
@SuppressWarnings("javadoc")
public class SoftwareAuthenticator {

	private static final JsonMapper JSON = new ObjectConverter().getJsonMapper();

	private final ClientPlatform _platform;

	/** A fresh authenticator in a browser at the given origin. */
	public SoftwareAuthenticator(String origin) {
		_platform = new ClientPlatform(new Origin(origin),
			new WebAuthnAuthenticatorAdaptor(new NoneAttestationAuthenticator()));
	}

	/** The browser moved to another origin, with the same authenticator. */
	public void setOrigin(String origin) {
		_platform.setOrigin(new Origin(origin));
	}

	/** What <code>navigator.credentials.create</code> answers for the given options. */
	public String create(String options) throws Exception {
		JsonNode node = JSON.readTree(options);
		List<PublicKeyCredentialParameters> parameters = new ArrayList<>();
		for (JsonNode parameter : node.get("pubKeyCredParams")) {
			parameters.add(new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY,
				COSEAlgorithmIdentifier.create(parameter.get("alg").asLong())));
		}
		List<PublicKeyCredentialDescriptor> exclude = new ArrayList<>();
		for (JsonNode descriptor : node.get("excludeCredentials")) {
			exclude.add(new PublicKeyCredentialDescriptor(PublicKeyCredentialType.PUBLIC_KEY,
				decode(descriptor.get("id").asString()), null));
		}
		JsonNode user = node.get("user");
		PublicKeyCredentialCreationOptions creation = new PublicKeyCredentialCreationOptions(
			new PublicKeyCredentialRpEntity(node.get("rp").get("id").asString(), node.get("rp").get("name").asString()),
			new PublicKeyCredentialUserEntity(decode(user.get("id").asString()), user.get("name").asString(),
				user.get("displayName").asString()),
			new DefaultChallenge(decode(node.get("challenge").asString())), parameters,
			Long.valueOf(node.get("timeout").asLong()), exclude,
			new AuthenticatorSelectionCriteria(null, Boolean.TRUE, ResidentKeyRequirement.REQUIRED,
				UserVerificationRequirement.PREFERRED),
			AttestationConveyancePreference.NONE, null);
		PublicKeyCredential<AuthenticatorAttestationResponse, ?> credential = _platform.create(creation);
		String id = encode(credential.getRawId());
		return JSON.writeValueAsString(Map.of(
			"id", id,
			"rawId", id,
			"type", "public-key",
			"response", Map.of(
				"clientDataJSON", encode(credential.getResponse().getClientDataJSON()),
				"attestationObject", encode(credential.getResponse().getAttestationObject()),
				"transports", List.of("internal")),
			"clientExtensionResults", Map.of()));
	}

	/** What <code>navigator.credentials.get</code> answers for the given options. */
	public String get(String options) throws Exception {
		JsonNode node = JSON.readTree(options);
		PublicKeyCredentialRequestOptions request = new PublicKeyCredentialRequestOptions(
			new DefaultChallenge(decode(node.get("challenge").asString())), Long.valueOf(node.get("timeout").asLong()),
			node.get("rpId").asString(), new ArrayList<>(), UserVerificationRequirement.PREFERRED, null);
		PublicKeyCredential<AuthenticatorAssertionResponse, ?> credential = _platform.get(request);
		String id = encode(credential.getRawId());
		AuthenticatorAssertionResponse response = credential.getResponse();
		return JSON.writeValueAsString(Map.of(
			"id", id,
			"rawId", id,
			"type", "public-key",
			"response", Map.of(
				"clientDataJSON", encode(response.getClientDataJSON()),
				"authenticatorData", encode(response.getAuthenticatorData()),
				"signature", encode(response.getSignature()),
				"userHandle", response.getUserHandle() == null ? "" : encode(response.getUserHandle())),
			"clientExtensionResults", Map.of()));
	}

	private static byte[] decode(String value) {
		return Base64.getUrlDecoder().decode(value);
	}

	private static String encode(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}
}
