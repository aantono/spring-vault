/*
 * Copyright 2016-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.vault.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.vault.VaultException;
import org.springframework.vault.support.Ciphertext;
import org.springframework.vault.support.Hmac;
import org.springframework.vault.support.Plaintext;
import org.springframework.vault.support.RawTransitKey;
import org.springframework.vault.support.Signature;
import org.springframework.vault.support.SignatureValidation;
import org.springframework.vault.support.TransitKeyType;
import org.springframework.vault.support.VaultDecryptionResult;
import org.springframework.vault.support.VaultEncryptionResult;
import org.springframework.vault.support.VaultHmacRequest;
import org.springframework.vault.support.VaultMount;
import org.springframework.vault.support.VaultSignRequest;
import org.springframework.vault.support.VaultSignatureVerificationRequest;
import org.springframework.vault.support.VaultTransitContext;
import org.springframework.vault.support.VaultTransitKey;
import org.springframework.vault.support.VaultTransitKeyConfiguration;
import org.springframework.vault.support.VaultTransitKeyCreationRequest;
import org.springframework.vault.util.IntegrationTestSupport;
import org.springframework.vault.util.RequiresVaultVersion;
import org.springframework.vault.util.Version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.fail;

/**
 * Integration tests for {@link VaultTransitTemplate} through
 * {@link VaultTransitOperations}.
 *
 * @author Mark Paluch
 * @author Praveendra Singh
 * @author Luander Ribeiro
 * @author Mikko Koli
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = VaultIntegrationTestConfiguration.class)
class VaultTransitTemplateIntegrationTests extends IntegrationTestSupport {

	static final String KEY_EXPORT_INTRODUCED_IN_VERSION = "0.6.5";

	static final String BATCH_INTRODUCED_IN_VERSION = "0.6.5";

	static final String SIGN_VERIFY_INTRODUCED_IN_VERSION = "0.6.2";

	static final String ED25519_INTRODUCED_IN_VERSION = "0.7.3";

	@Autowired
	VaultOperations vaultOperations;

	VaultTransitOperations transitOperations;

	Version vaultVersion;

	@BeforeEach
	void before() {

		transitOperations = vaultOperations.opsForTransit();

		if (!vaultOperations.opsForSys().getMounts().containsKey("transit/")) {
			vaultOperations.opsForSys().mount("transit", VaultMount.create("transit"));
		}

		vaultVersion = prepare().getVersion();

		removeKeys();
	}

	@AfterEach
	void tearDown() {
		removeKeys();
	}

	private void deleteKey(String keyName) {

		try {
			transitOperations.configureKey(keyName,
					VaultTransitKeyConfiguration.builder().deletionAllowed(true).build());
		}
		catch (Exception e) {
		}

		try {
			transitOperations.deleteKey(keyName);
		}
		catch (Exception e) {
		}
	}

	private void removeKeys() {

		if (vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.6.4"))) {
			List<String> keys = vaultOperations.opsForTransit().getKeys();
			for (String keyName : keys) {
				deleteKey(keyName);
			}
		}
		else {
			deleteKey("mykey");
			deleteKey("export");
			deleteKey("ecdsa-key");
			deleteKey("ed-key");
		}
	}

	@Test
	void createKeyShouldCreateKey() {

		transitOperations.createKey("mykey");

		VaultTransitKey mykey = transitOperations.getKey("mykey");

		assertThat(mykey.getType()).startsWith("aes");

		assertThat(mykey.getName()).isEqualTo("mykey");
		assertThat(mykey.isDeletionAllowed()).isFalse();
		assertThat(mykey.isDerived()).isFalse();
		assertThat(mykey.getMinDecryptionVersion()).isEqualTo(1);
		assertThat(mykey.getLatestVersion()).isEqualTo(1);

		if (vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.7.0"))) {

			assertThat(mykey.supportsDecryption()).isTrue();
			assertThat(mykey.supportsEncryption()).isTrue();
			assertThat(mykey.supportsDerivation()).isTrue();
			assertThat(mykey.supportsSigning()).isFalse();
		}
	}

	@Test
	@RequiresVaultVersion("0.6.4")
	void createKeyShouldCreateEcDsaKey() {

		String keyName = createEcdsaP256Key();

		VaultTransitKey mykey = transitOperations.getKey(keyName);

		assertThat(mykey.getType()).startsWith("ecdsa");
		assertThat(mykey.getKeys()).isNotEmpty();
	}

	@Test
	@RequiresVaultVersion(ED25519_INTRODUCED_IN_VERSION)
	void createKeyShouldCreateEdKey() {

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest
				.ofKeyType("ed25519");

		transitOperations.createKey("ed-key", request);

		VaultTransitKey mykey = transitOperations.getKey("ed-key");

		assertThat(mykey.getType()).startsWith("ed");
		assertThat(mykey.getKeys()).isNotEmpty();
	}

	@Test
	void createKeyShouldCreateKeyWithOptions() {

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder() //
				.convergentEncryption(true) //
				.derived(true) //
				.build();

		transitOperations.createKey("mykey", request);

		VaultTransitKey mykey = transitOperations.getKey("mykey");

		assertThat(mykey.getName()).isEqualTo("mykey");
		assertThat(mykey.isDeletionAllowed()).isFalse();
		assertThat(mykey.isDerived()).isTrue();
		assertThat(mykey.getMinDecryptionVersion()).isEqualTo(1);
		assertThat(mykey.getLatestVersion()).isEqualTo(1);
	}

	@Test
	void shouldConfigureKey() {

		transitOperations.createKey("mykey");
		transitOperations.rotate("mykey");
		transitOperations.rotate("mykey");

		VaultTransitKeyConfiguration configuration = VaultTransitKeyConfiguration
				.builder().deletionAllowed(true).minDecryptionVersion(1)
				.minEncryptionVersion(2).build();

		transitOperations.configureKey("mykey", configuration);

		VaultTransitKey mykey = transitOperations.getKey("mykey");

		assertThat(mykey.getMinDecryptionVersion()).isEqualTo(1);

		if (vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.8.0"))) {
			assertThat(mykey.getMinEncryptionVersion()).isEqualTo(2);
		}
		else {
			assertThat(mykey.getMinEncryptionVersion()).isEqualTo(0);
		}
	}

	@Test
	@RequiresVaultVersion("0.6.4")
	void shouldEnumerateKey() {

		assertThat(transitOperations.getKeys()).isEmpty();

		transitOperations.createKey("mykey");

		assertThat(transitOperations.getKeys()).contains("mykey");
	}

	@Test
	void getKeyShouldReturnNullIfKeyNotExists() {

		VaultTransitKey key = transitOperations.getKey("hello-world");
		assertThat(key).isNull();
	}

	@Test
	void deleteKeyShouldFailIfKeyNotExists() {

		try {
			transitOperations.deleteKey("hello-world");
			fail("Missing VaultException");
		}
		catch (VaultException e) {
			assertThat(e).hasMessageContaining("Status 400");
		}
	}

	@Test
	void deleteKeyShouldDeleteKey() {

		transitOperations.createKey("mykey");
		transitOperations.configureKey("mykey",
				VaultTransitKeyConfiguration.builder().deletionAllowed(true).build());
		transitOperations.deleteKey("mykey");

		assertThat(transitOperations.getKey("mykey")).isNull();
	}

	@Test
	void encryptShouldCreateCiphertext() {

		transitOperations.createKey("mykey");

		String ciphertext = transitOperations.encrypt("mykey", "hello-world");
		assertThat(ciphertext).startsWith("vault:v");
	}

	@Test
	void encryptShouldCreateCiphertextWithNonceAndContext() {

		transitOperations.createKey("mykey", VaultTransitKeyCreationRequest.builder()
				.convergentEncryption(true).derived(true).build());

		VaultTransitContext transitRequest = VaultTransitContext.builder()
				.context("blubb".getBytes()) //
				.nonce("123456789012".getBytes()) //
				.build();

		String ciphertext = transitOperations.encrypt("mykey", "hello-world".getBytes(),
				transitRequest);
		assertThat(ciphertext).startsWith("vault:v1:");
	}

	@Test
	@RequiresVaultVersion(BATCH_INTRODUCED_IN_VERSION)
	void encryptShouldEncryptEmptyValues() {

		transitOperations.createKey("mykey", VaultTransitKeyCreationRequest.builder()
				.convergentEncryption(true).derived(true).build());

		VaultTransitContext context = VaultTransitContext.builder()
				.context("blubb".getBytes()) //
				.nonce("123456789012".getBytes()) //
				.build();

		Ciphertext ciphertext = transitOperations.encrypt("mykey",
				Plaintext.of("").with(context));

		assertThat(ciphertext.getCiphertext()).startsWith("vault:v1:");
		assertThat(ciphertext.getContext()).isEqualTo(context);
	}

	@Test
	void encryptShouldCreateWrappedCiphertextWithNonceAndContext() {

		transitOperations.createKey("mykey", VaultTransitKeyCreationRequest.builder()
				.convergentEncryption(true).derived(true).build());

		VaultTransitContext context = VaultTransitContext.builder()
				.context("blubb".getBytes()) //
				.nonce("123456789012".getBytes()) //
				.build();

		Ciphertext ciphertext = transitOperations.encrypt("mykey",
				Plaintext.of("hello-world").with(context));

		assertThat(ciphertext.getCiphertext()).startsWith("vault:v1:");
		assertThat(ciphertext.getContext()).isEqualTo(context);
	}

	@Test
	void decryptShouldCreatePlaintext() {

		transitOperations.createKey("mykey");

		String ciphertext = transitOperations.encrypt("mykey", "hello-world");
		String plaintext = transitOperations.decrypt("mykey", ciphertext);

		assertThat(plaintext).isEqualTo("hello-world");
	}

	@Test
	void decryptShouldCreatePlaintextWithNonceAndContext() {

		transitOperations.createKey("mykey", VaultTransitKeyCreationRequest.builder()
				.convergentEncryption(true).derived(true).build());

		VaultTransitContext transitRequest = VaultTransitContext.builder() //
				.context("blubb".getBytes()) //
				.nonce("123456789012".getBytes()) //
				.build();

		String ciphertext = transitOperations.encrypt("mykey", "hello-world".getBytes(),
				transitRequest);

		byte[] plaintext = transitOperations.decrypt("mykey", ciphertext, transitRequest);
		assertThat(new String(plaintext)).isEqualTo("hello-world");
	}

	@Test
	void decryptShouldCreateWrappedPlaintextWithNonceAndContext() {

		transitOperations.createKey("mykey", VaultTransitKeyCreationRequest.builder()
				.convergentEncryption(true).derived(true).build());

		VaultTransitContext context = VaultTransitContext.builder() //
				.context("blubb".getBytes()) //
				.nonce("123456789012".getBytes()) //
				.build();

		Ciphertext ciphertext = transitOperations.encrypt("mykey",
				Plaintext.of("hello-world").with(context));
		Plaintext plaintext = transitOperations.decrypt("mykey", ciphertext);

		assertThat(plaintext.asString()).isEqualTo("hello-world");
		assertThat(plaintext.getContext()).isEqualTo(context);
	}

	@Test
	void encryptAndRewrapShouldCreateCiphertext() {

		transitOperations.createKey("mykey");

		String ciphertext = transitOperations.encrypt("mykey", "hello-world");
		transitOperations.rotate("mykey");

		String rewrapped = transitOperations.rewrap("mykey", ciphertext);

		assertThat(rewrapped).startsWith("vault:v2:");
	}

	@Test
	void shouldEncryptBinaryPlaintext() {

		transitOperations.createKey("mykey");

		byte[] plaintext = new byte[] { 1, 2, 3, 4, 5 };

		String ciphertext = transitOperations.encrypt("mykey", plaintext,
				VaultTransitContext.empty());

		byte[] decrypted = transitOperations.decrypt("mykey", ciphertext,
				VaultTransitContext.empty());

		assertThat(decrypted).isEqualTo(plaintext);
	}

	@Test
	void encryptAndRewrapShouldCreateCiphertextWithNonceAndContext() {

		transitOperations.createKey("mykey", VaultTransitKeyCreationRequest.builder()
				.convergentEncryption(true).derived(true).build());

		VaultTransitContext transitRequest = VaultTransitContext.builder() //
				.context("blubb".getBytes()) //
				.nonce("123456789012".getBytes()) //
				.build();

		String ciphertext = transitOperations.encrypt("mykey", "hello-world".getBytes(),
				transitRequest);
		transitOperations.rotate("mykey");

		String rewrapped = transitOperations.rewrap("mykey", ciphertext, transitRequest);
		assertThat(rewrapped).startsWith("vault:v2");
	}

	@Test
	@RequiresVaultVersion(BATCH_INTRODUCED_IN_VERSION)
	void shouldBatchEncrypt() {

		transitOperations.createKey("mykey");

		List<VaultEncryptionResult> encrypted = transitOperations.encrypt("mykey",
				Arrays.asList(Plaintext.of("one"), Plaintext.of("two")));

		assertThat(encrypted.get(0).get().getCiphertext()).startsWith("vault:");
		assertThat(encrypted.get(1).get().getCiphertext()).startsWith("vault:");
	}

	@Test
	@RequiresVaultVersion(BATCH_INTRODUCED_IN_VERSION)
	void shouldBatchDecrypt() {

		transitOperations.createKey("mykey");

		Ciphertext one = transitOperations.encrypt("mykey", Plaintext.of("one"));
		Ciphertext two = transitOperations.encrypt("mykey", Plaintext.of("two"));

		Plaintext plainOne = transitOperations.decrypt("mykey", one);
		Plaintext plainTwo = transitOperations.decrypt("mykey", two);

		List<VaultDecryptionResult> decrypted = transitOperations.decrypt("mykey",
				Arrays.asList(one, two));

		assertThat(decrypted.get(0).get()).isEqualTo(plainOne);
		assertThat(decrypted.get(0).getAsString()).isEqualTo("one");
		assertThat(decrypted.get(1).get()).isEqualTo(plainTwo);
		assertThat(decrypted.get(1).getAsString()).isEqualTo("two");
	}

	@Test
	@RequiresVaultVersion(BATCH_INTRODUCED_IN_VERSION)
	void shouldBatchEncryptWithContext() {

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder() //
				.derived(true) //
				.build();

		transitOperations.createKey("mykey", request);

		Plaintext one = Plaintext.of("one").with(
				VaultTransitContext.builder().context("oneContext".getBytes()).build());

		Plaintext two = Plaintext.of("two").with(
				VaultTransitContext.builder().context("twoContext".getBytes()).build());

		List<VaultEncryptionResult> encrypted = transitOperations.encrypt("mykey",
				Arrays.asList(one, two));

		assertThat(encrypted.get(0).get().getContext()).isEqualTo(one.getContext());
		assertThat(encrypted.get(1).get().getContext()).isEqualTo(two.getContext());
	}

	@Test
	@RequiresVaultVersion(BATCH_INTRODUCED_IN_VERSION)
	void shouldBatchDecryptWithContext() {

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder() //
				.derived(true) //
				.build();

		transitOperations.createKey("mykey", request);

		Plaintext one = Plaintext.of("one").with(
				VaultTransitContext.builder().context("oneContext".getBytes()).build());

		Plaintext two = Plaintext.of("two").with(
				VaultTransitContext.builder().context("twoContext".getBytes()).build());

		List<VaultEncryptionResult> encrypted = transitOperations.encrypt("mykey",
				Arrays.asList(one, two));
		List<VaultDecryptionResult> decrypted = transitOperations.decrypt("mykey",
				Arrays.asList(encrypted.get(0).get(), encrypted.get(1).get()));

		assertThat(decrypted.get(0).get()).isEqualTo(one);
		assertThat(decrypted.get(1).get()).isEqualTo(two);
	}

	@Test
	@RequiresVaultVersion(BATCH_INTRODUCED_IN_VERSION)
	void shouldBatchDecryptWithWrongContext() {

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder() //
				.derived(true) //
				.build();

		transitOperations.createKey("mykey", request);

		Plaintext one = Plaintext.of("one").with(
				VaultTransitContext.builder().context("oneContext".getBytes()).build());

		Plaintext two = Plaintext.of("two").with(
				VaultTransitContext.builder().context("twoContext".getBytes()).build());

		List<VaultEncryptionResult> encrypted = transitOperations.encrypt("mykey",
				Arrays.asList(one, two));

		Ciphertext encryptedOne = encrypted.get(0).get();
		Ciphertext decryptedTwo = encrypted.get(1).get();

		Ciphertext tampered = decryptedTwo.with(encryptedOne.getContext());

		List<VaultDecryptionResult> decrypted = transitOperations.decrypt("mykey",
				Arrays.asList(encryptedOne, tampered));

		assertThat(decrypted.get(0).get()).isEqualTo(one);
		assertThat(decrypted.get(1).isSuccessful()).isEqualTo(false);
		assertThat(decrypted.get(1).getCause()).isInstanceOf(VaultException.class);
	}

	@Test
	@RequiresVaultVersion(BATCH_INTRODUCED_IN_VERSION)
	void shouldBatchDecryptEmptyPlaintext() {

		transitOperations.createKey("mykey");

		Ciphertext empty = transitOperations.encrypt("mykey", Plaintext.empty());

		List<VaultDecryptionResult> decrypted = transitOperations.decrypt("mykey",
				Collections.singletonList(empty));

		assertThat(decrypted.get(0).getAsString()).isEqualTo("");
	}

	@Test
	@RequiresVaultVersion(BATCH_INTRODUCED_IN_VERSION)
	void shouldBatchDecryptEmptyPlaintextWithContext() {

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder() //
				.derived(true) //
				.build();

		transitOperations.createKey("mykey", request);

		Plaintext empty = Plaintext.empty().with(
				VaultTransitContext.builder().context("oneContext".getBytes()).build());

		List<VaultEncryptionResult> encrypted = transitOperations.encrypt("mykey",
				Collections.singletonList(empty));
		List<VaultDecryptionResult> decrypted = transitOperations.decrypt("mykey",
				Collections.singletonList(encrypted.get(0).get()));

		assertThat(decrypted.get(0).get()).isEqualTo(empty);
	}

	@Test
	@RequiresVaultVersion(SIGN_VERIFY_INTRODUCED_IN_VERSION)
	void generateHmacShouldCreateHmac() {

		String keyName = createEcdsaP256Key();

		Hmac hmac = transitOperations.getHmac(keyName, Plaintext.of("hello-world"));
		assertThat(hmac.getHmac()).isNotEmpty();
	}

	@Test
	@RequiresVaultVersion(SIGN_VERIFY_INTRODUCED_IN_VERSION)
	void generateHmacShouldCreateHmacForRotatedKey() {

		String keyName = createEcdsaP256Key();
		transitOperations.rotate(keyName);

		VaultHmacRequest request = VaultHmacRequest.builder()
				.plaintext(Plaintext.of("hello-world")).keyVersion(2).build();

		Hmac hmac = transitOperations.getHmac(keyName, request);
		assertThat(hmac.getHmac()).isNotEmpty();
	}

	@Test
	@RequiresVaultVersion(SIGN_VERIFY_INTRODUCED_IN_VERSION)
	void generateHmacWithCustomAlgorithmShouldCreateHmac() {

		String keyName = createEcdsaP256Key();

		VaultHmacRequest request = VaultHmacRequest.builder()
				.plaintext(Plaintext.of("hello-world")).algorithm("sha2-512").build();

		Hmac hmac = transitOperations.getHmac(keyName, request);
		assertThat(hmac.getHmac()).isNotEmpty();
	}

	@Test
	void generateHmacWithInvalidAlgorithmShouldFail() {

		String keyName = createEcdsaP256Key();

		VaultHmacRequest request = VaultHmacRequest.builder()
				.plaintext(Plaintext.of("hello-world")).algorithm("blah-512").build();

		assertThatExceptionOfType(VaultException.class)
				.isThrownBy(() -> transitOperations.getHmac(keyName, request));
	}

	@Test
	@RequiresVaultVersion(SIGN_VERIFY_INTRODUCED_IN_VERSION)
	void signShouldCreateSignature() {

		String keyName = createEcdsaP256Key();

		Signature signature = transitOperations.sign(keyName,
				Plaintext.of("hello-world"));
		assertThat(signature.getSignature()).isNotEmpty();
	}

	@Test
	@RequiresVaultVersion(ED25519_INTRODUCED_IN_VERSION)
	void signShouldCreateSignatureUsingEd25519() {

		VaultTransitKeyCreationRequest keyCreationRequest = VaultTransitKeyCreationRequest
				.ofKeyType("ed25519");
		transitOperations.createKey("ed-key", keyCreationRequest);

		Signature signature = transitOperations.sign("ed-key",
				Plaintext.of("hello-world"));
		assertThat(signature.getSignature()).isNotEmpty();
	}

	@Test
	void signWithInvalidKeyFormatShouldFail() {

		transitOperations.createKey("mykey");

		assertThatExceptionOfType(VaultException.class).isThrownBy(
				() -> transitOperations.sign("mykey", Plaintext.of("hello-world")));
	}

	@Test
	@RequiresVaultVersion(SIGN_VERIFY_INTRODUCED_IN_VERSION)
	void signWithCustomAlgorithmShouldCreateSignature() {

		String keyName = createEcdsaP256Key();

		Plaintext plaintext = Plaintext.of("hello-world");
		VaultSignRequest request = VaultSignRequest.builder().plaintext(plaintext)
				.algorithm("sha2-512").build();

		Signature signature = transitOperations.sign(keyName, request);
		assertThat(signature.getSignature()).isNotEmpty();
	}

	@Test
	@RequiresVaultVersion(SIGN_VERIFY_INTRODUCED_IN_VERSION)
	void shouldVerifyValidSignature() {

		String keyName = createEcdsaP256Key();

		Plaintext plaintext = Plaintext.of("hello-world");
		Signature signature = transitOperations.sign(keyName, plaintext);

		boolean valid = transitOperations.verify(keyName, plaintext, signature);
		assertThat(valid).isTrue();
	}

	@Test
	@RequiresVaultVersion(SIGN_VERIFY_INTRODUCED_IN_VERSION)
	void shouldVerifyValidHmac() {

		String keyName = createEcdsaP256Key();

		Plaintext plaintext = Plaintext.of("hello-world");
		Hmac hmac = transitOperations.getHmac(keyName, plaintext);

		SignatureValidation valid = transitOperations.verify(keyName,
				VaultSignatureVerificationRequest.create(plaintext, hmac));
		assertThat(valid).isEqualTo(SignatureValidation.valid());
	}

	@Test
	@RequiresVaultVersion(SIGN_VERIFY_INTRODUCED_IN_VERSION)
	void shouldVerifyValidSignatureWithCustomAlgorithm() {

		String keyName = createEcdsaP256Key();

		Plaintext plaintext = Plaintext.of("hello-world");
		VaultSignRequest request = VaultSignRequest.builder().plaintext(plaintext)
				.algorithm("sha2-512").build();

		Signature signature = transitOperations.sign(keyName, request);

		VaultSignatureVerificationRequest verificationRequest = VaultSignatureVerificationRequest
				.builder().algorithm("sha2-512").plaintext(plaintext).signature(signature)
				.build();

		SignatureValidation valid = transitOperations.verify(keyName,
				verificationRequest);
		assertThat(valid).isEqualTo(SignatureValidation.valid());
	}

	@Test
	@RequiresVaultVersion(KEY_EXPORT_INTRODUCED_IN_VERSION)
	void shouldCreateNewExportableKey() {

		VaultTransitOperations vaultTransitOperations = vaultOperations.opsForTransit();
		VaultTransitKeyCreationRequest vaultTransitKeyCreationRequest = VaultTransitKeyCreationRequest
				.builder().exportable(true).derived(true).build();

		vaultTransitOperations.createKey("export-test", vaultTransitKeyCreationRequest);

		VaultTransitKey vaultTransitKey = vaultTransitOperations.getKey("export-test");

		assertThat(vaultTransitKey.getName()).isEqualTo("export-test");
		assertThat(vaultTransitKey.isExportable()).isTrue();
	}

	@Test
	@RequiresVaultVersion(KEY_EXPORT_INTRODUCED_IN_VERSION)
	void shouldCreateNotExportableKeyByDefault() {

		VaultTransitOperations vaultTransitOperations = vaultOperations.opsForTransit();

		vaultTransitOperations.createKey("no-export");

		VaultTransitKey vaultTransitKey = vaultTransitOperations.getKey("no-export");

		assertThat(vaultTransitKey.getName()).isEqualTo("no-export");
		assertThat(vaultTransitKey.isExportable()).isFalse();
	}

	@Test
	@RequiresVaultVersion(KEY_EXPORT_INTRODUCED_IN_VERSION)
	void shouldExportEncryptionKey() {

		vaultOperations.write("transit/keys/export",
				Collections.singletonMap("exportable", true));

		RawTransitKey rawTransitKey = transitOperations.exportKey("export",
				TransitKeyType.ENCRYPTION_KEY);

		assertThat(rawTransitKey.getName()).isEqualTo("export");
		assertThat(rawTransitKey.getKeys()).isNotEmpty();
		assertThat(rawTransitKey.getKeys().get("1")).isNotBlank();
	}

	@Test
	@RequiresVaultVersion(KEY_EXPORT_INTRODUCED_IN_VERSION)
	void shouldNotAllowExportSigningKey() {

		vaultOperations.write("transit/keys/export",
				Collections.singletonMap("exportable", true));

		assertThatExceptionOfType(VaultException.class).isThrownBy(
				() -> transitOperations.exportKey("export", TransitKeyType.SIGNING_KEY));
	}

	@Test
	@RequiresVaultVersion(KEY_EXPORT_INTRODUCED_IN_VERSION)
	void shouldExportHmacKey() {

		vaultOperations.write("transit/keys/export",
				Collections.singletonMap("exportable", true));

		RawTransitKey rawTransitKey = transitOperations.exportKey("export",
				TransitKeyType.HMAC_KEY);

		assertThat(rawTransitKey.getName()).isEqualTo("export");
		assertThat(rawTransitKey.getKeys()).isNotEmpty();
		assertThat(rawTransitKey.getKeys().get("1")).isNotBlank();
	}

	@Test
	@RequiresVaultVersion(KEY_EXPORT_INTRODUCED_IN_VERSION)
	void shouldExportEcDsaKey() {

		VaultTransitOperations transitOperations = vaultOperations.opsForTransit();

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder()
				.type("ecdsa-p256").exportable(true).build();

		transitOperations.createKey("ecdsa-key", request);

		RawTransitKey hmacKey = transitOperations.exportKey("ecdsa-key",
				TransitKeyType.HMAC_KEY);
		RawTransitKey signingKey = transitOperations.exportKey("ecdsa-key",
				TransitKeyType.SIGNING_KEY);

		assertThat(hmacKey.getKeys()).isNotEmpty();
		assertThat(signingKey.getKeys()).isNotEmpty();
	}

	@Test
	@RequiresVaultVersion(ED25519_INTRODUCED_IN_VERSION)
	void shouldExportEdKey() {

		VaultTransitOperations transitOperations = vaultOperations.opsForTransit();

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder()
				.type("ed25519").exportable(true).build();

		transitOperations.createKey("ed-key", request);

		RawTransitKey hmacKey = transitOperations.exportKey("ed-key",
				TransitKeyType.HMAC_KEY);
		RawTransitKey signingKey = transitOperations.exportKey("ed-key",
				TransitKeyType.SIGNING_KEY);

		assertThat(hmacKey.getKeys()).isNotEmpty();
		assertThat(signingKey.getKeys()).isNotEmpty();
	}

	private String createEcdsaP256Key() {

		String keyName = "ecdsa-key";
		VaultTransitKeyCreationRequest keyCreationRequest = VaultTransitKeyCreationRequest
				.ofKeyType("ecdsa-p256");
		transitOperations.createKey(keyName, keyCreationRequest);

		return keyName;
	}
}
