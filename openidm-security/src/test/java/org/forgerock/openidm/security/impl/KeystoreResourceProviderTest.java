/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2014-2016 ForgeRock AS.
 */

package org.forgerock.openidm.security.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.forgerock.json.resource.Router.uriTemplate;
import static org.forgerock.json.resource.test.assertj.AssertJActionResponseAssert.assertThat;
import static org.forgerock.json.test.assertj.AssertJJsonValueAssert.assertThat;
import static org.forgerock.security.keystore.KeyStoreType.JCEKS;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.UnrecoverableEntryException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import org.apache.commons.lang3.StringUtils;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.ActionResponse;
import org.forgerock.json.resource.Connection;
import org.forgerock.json.resource.Requests;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.Resources;
import org.forgerock.json.resource.Router;
import org.forgerock.openidm.repo.RepositoryService;
import org.forgerock.openidm.security.KeyStoreHandler;
import org.forgerock.openidm.security.KeyStoreManager;
import org.forgerock.openidm.util.DateUtil;
import org.forgerock.services.context.RootContext;
import org.forgerock.util.encode.Base64;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class KeystoreResourceProviderTest {

    public static final String BEGIN_CERT = "-----BEGIN CERTIFICATE-----";
    public static final String END_CERT = "-----END CERTIFICATE-----";

    private final Router router = new Router();
    private final Connection connection = Resources.newInternalConnection(router);
    private final String KEYSTORE = "keystore";
    private final String KEYSTORE_ROUTE = "security/keystore";
    private final String KEYSTORE_PASSWORD = "changeit";
    private final String TEST_CERT_ALIAS = "testCert";
    private KeyStoreHandler keyStoreHandler;
    private File keystoreFile;

    private KeyStoreManager keyStoreManager;
    private RepositoryService repositoryService;
    private KeystoreResourceProvider keystoreResourceProvider;

    private static class MockKeyStoreManager implements KeyStoreManager {
        KeyStoreHandler keyStoreHandler;
        KeyStoreHandler trustStoreHandler;

        public MockKeyStoreManager(KeyStoreHandler keyStoreHandler, KeyStoreHandler trustStoreHandler) {
            this.keyStoreHandler = keyStoreHandler;
            this.trustStoreHandler = trustStoreHandler;
        }

        @Override
        public void reload() throws Exception {
            if (trustStoreHandler != null) {
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStoreHandler.getStore());
            }

            if (keyStoreHandler != null) {
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keyStoreHandler.getStore(), keyStoreHandler.getPassword().toCharArray());
            }
        }
    }

    @BeforeMethod
    public void setup() {
        keyStoreManager = new MockKeyStoreManager(keyStoreHandler, null);
        repositoryService = mock(RepositoryService.class);
        keystoreResourceProvider =
                new KeystoreResourceProvider(KEYSTORE, keyStoreHandler, keyStoreManager, repositoryService);
        router.addRoute(uriTemplate(KEYSTORE_ROUTE), keystoreResourceProvider);
    }

    @AfterMethod
    public void teardown() throws KeyStoreException {
        router.removeAllRoutes();
        keyStoreHandler.getStore().deleteEntry(TEST_CERT_ALIAS);
    }

    @BeforeClass
    public void runInitalSetup() throws Exception {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

        KeyStore keyStore = KeyStore.getInstance(JCEKS.name());
        keyStore.load(null, KEYSTORE_PASSWORD.toCharArray());

        keystoreFile = File.createTempFile(KEYSTORE, JCEKS.name());
        FileOutputStream fileOutputStream = new FileOutputStream(keystoreFile);
        keyStore.store(fileOutputStream, KEYSTORE_PASSWORD.toCharArray());
        fileOutputStream.close();

        keyStoreHandler = new FileBasedKeyStoreHandler(JCEKS, keystoreFile.getAbsolutePath(), KEYSTORE_PASSWORD);
    }

    @AfterClass
    public void runFinalTearDown() {
        keystoreFile.delete();
        router.removeAllRoutes();
        connection.close();
    }

    @Test
    public void testActionGenerateCertReturningPrivateKey()
            throws ResourceException, KeyStoreException, CertificateEncodingException,
            UnrecoverableEntryException, NoSuchAlgorithmException {
        //given
        ActionRequest actionRequest =
                Requests.newActionRequest(KEYSTORE_ROUTE, KeystoreResourceProvider.ACTION_GENERATE_CERT);
        actionRequest.setContent(createGenerateCertActionContent(true));

        //when
        final ActionResponse result = connection.action(new RootContext(), actionRequest);

        //then
        assertThat(result).withContent().hasObject("privateKey");
        checkResultForRequiredFields(result.getJsonContent());
        checkKeyStoreEntry(result.getJsonContent());
    }

    @Test
    public void testActionGenerateCertNotReturningPrivateKey()
            throws ResourceException, NoSuchAlgorithmException, CertificateEncodingException,
            UnrecoverableEntryException, KeyStoreException {
        //given
        ActionRequest actionRequest =
                Requests.newActionRequest(KEYSTORE_ROUTE, KeystoreResourceProvider.ACTION_GENERATE_CERT);
        actionRequest.setContent(createGenerateCertActionContent(true));

        //when
        final ActionResponse result = connection.action(new RootContext(), actionRequest);

        //then
        assertThat(result).withContent().hasObject("privateKey");
        checkResultForRequiredFields(result.getJsonContent());
        checkKeyStoreEntry(result.getJsonContent());
    }

    @Test(expectedExceptions = ResourceException.class)
    public void testActionGenerateCertWithoutAlias()
            throws ResourceException, KeyStoreException, CertificateEncodingException,
            UnrecoverableEntryException, NoSuchAlgorithmException {
        //given
        ActionRequest actionRequest =
                Requests.newActionRequest(KEYSTORE_ROUTE, KeystoreResourceProvider.ACTION_GENERATE_CERT);
        final JsonValue content = createGenerateCertActionContent(true);
        content.remove("alias");
        actionRequest.setContent(content);

        //when
        connection.action(new RootContext(), actionRequest);
    }

    @Test(expectedExceptions = ResourceException.class)
    public void testActionGenerateCertWithAliasAlreadyInUse()
            throws ResourceException, KeyStoreException, CertificateEncodingException,
            UnrecoverableEntryException, NoSuchAlgorithmException {
        //given
        ActionRequest actionRequest =
                Requests.newActionRequest(KEYSTORE_ROUTE, KeystoreResourceProvider.ACTION_GENERATE_CERT);
        actionRequest.setContent(createGenerateCertActionContent(true));

        //when
        connection.action(new RootContext(), actionRequest);
        connection.action(new RootContext(), actionRequest);
    }

    private JsonValue createGenerateCertActionContent(final boolean returnPrivateKey) {
        final DateUtil dateUtil = DateUtil.getDateUtil();
        final Map<String,Object> content = new HashMap<>();
        content.put("alias", TEST_CERT_ALIAS);
        content.put("algorithm", KeystoreResourceProvider.DEFAULT_ALGORITHM);
        content.put("signatureAlgorithm", KeystoreResourceProvider.DEFAULT_SIGNATURE_ALGORITHM);
        content.put("keySize", KeystoreResourceProvider.DEFAULT_KEY_SIZE);
        content.put("domainName","domainName");
        content.put("validFrom", dateUtil.now());
        content.put("validTo", dateUtil.parseIfDate(dateUtil.currentDateTime().plusDays(1).toDate().toString()));
        content.put("returnPrivateKey", returnPrivateKey);
        return new JsonValue(content);
    }

    private void checkResultForRequiredFields(final JsonValue result) {
        assertThat(result).isNotNull();
        assertThat(result).hasString("_id");
        assertThat(result).hasString("type");
        assertThat(result).hasString("cert");
        assertThat(result).hasObject("publicKey");
    }

    private void checkKeyStoreEntry(final JsonValue result)
            throws UnrecoverableEntryException, NoSuchAlgorithmException, KeyStoreException,
            CertificateEncodingException {
        KeyStore.PrivateKeyEntry privateKeyEntry =
                (KeyStore.PrivateKeyEntry) keyStoreHandler
                        .getStore()
                        .getEntry(TEST_CERT_ALIAS, new KeyStore.PasswordProtection(KEYSTORE_PASSWORD.toCharArray()));
        assertThat(privateKeyEntry).isNotNull();

        Certificate certificate = privateKeyEntry.getCertificate();
        assertThat(certificate != null);

        final String certAsPEM = convertCertToPEM(certificate.getEncoded());
        assertThat(certAsPEM != null || !StringUtils.isBlank(certAsPEM));
        certAsPEM.equals(result.get("cert").asString());

        PrivateKey privateKey = privateKeyEntry.getPrivateKey();
        assertThat(privateKey != null);
    }

    private String convertCertToPEM(final byte[] encodedCert) {
        final StringBuilder certAsPEM = new StringBuilder();
        certAsPEM.append(BEGIN_CERT)
                .append(Base64.encode(encodedCert))
                .append(END_CERT);
        return  certAsPEM.toString();
    }
}
