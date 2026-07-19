/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.keystore;

import com.matera.x9qrcode.app.service.KeystoreRetriever;
import com.matera.x9qrcode.infrastructure.configuration.property.keystore.KeystoreProperties;
import com.matera.x9qrcode.infrastructure.configuration.property.keystore.KeystoreProperties.KeyStoreLoadingStrategy;

import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyStore;
import java.security.KeyStore.Entry;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public abstract class AbstractKeystoreRetriever implements KeystoreRetriever {

    public static final String ERROR_TRYING_TO_READ_ITS_CREATION_TIMESTAMP =
        "Error trying to read its creation timestamp";
    private static final String ERROR_TRYING_TO_GET_FILE_BY_PATH = "Error trying to get file by resource";

    protected KeyStore keystore;

    protected ResourceLoader resourceLoader;

    protected Resource resource;

    protected String keystoreDescription;

    protected KeystoreProperties keystoreProperties;

    protected long lastLoadingTime;

    protected long lastCreationTime;

    protected final Map<String, Entry> entriesCache = new ConcurrentHashMap<>();

    public AbstractKeystoreRetriever(KeystoreProperties keystoreProperties,
                                     String keystoreDescription,
                                     ResourceLoader resourceLoader) {
        Assert.hasText(keystoreDescription, "Keystore must have a description.");

        this.keystoreProperties = keystoreProperties;

        this.keystoreDescription = keystoreDescription;

        this.resourceLoader = resourceLoader;

        instantiateResourceIfStrategyUsesOne();

        keystore = instantiateKeystore();

        loadKeystore();

        if (log.isTraceEnabled()) {
            try {
                log.trace("Aliases found on keystore: {}", Collections.list(keystore.aliases()));
            } catch (Exception e) {
                log.trace("Error trying to get aliases from keystore: {}", e.getMessage());
            }
        }
    }

    abstract void clearCache();

    @Override
    public List<String> getAliases() {
        try {
            return Collections.list(keystore.aliases());
        } catch (KeyStoreException e) {
            throw new RuntimeException("Failed to retrieve aliases from keystore %s".formatted(keystoreDescription), e);
        }
    }

    @Override
    public String getDescription() {
        return keystoreDescription;
    }


    @Override
    public String getKeystoreAlias() {
        return keystoreProperties.getAlias();
    }


    @Override
    public boolean reloadIfNotUpToDate() {
        switch (keystoreProperties.getLoadStrategy()) {
            case DO_NOT_LOAD:
            case LOAD_WITH_ONLY_PASSWORD:
                log.trace("No point to reload keystore {} with strategy {}", keystoreDescription,
                    keystoreProperties.getLoadStrategy());
                return false;

            case LOAD_INPUT_STREAM:
            default:
                // We need to instantiate the resource again because some resource types
                //  do not return an updated LastModifiedTimestamp.
                instantiateResourceIfStrategyUsesOne();
                if (isNewCertificatedFileToUpdate()) {
                    log.debug("Reloading keystore {}.", keystoreDescription);
                    clearCache();
                    log.debug("Keystore {} reloaded successfully.", keystoreDescription);
                    return true;
                } else {
                    return false;
                }
        }
    }

    protected KeystoreProperties getKeyStoreProperties() {
        return keystoreProperties;
    }

    protected String getClassName(Object object) {
        if (object == null) {
            return "null";
        }
        Class<?> clazz = object.getClass();
        return clazz.getName();
    }

    private void instantiateResourceIfStrategyUsesOne() {

        if (KeyStoreLoadingStrategy.LOAD_INPUT_STREAM.equals(keystoreProperties.getLoadStrategy())) {
            Assert.notNull(keystoreProperties.getLocation(),
                "when using the load strategy %s, the configuration of the keystore location is required"
                    .formatted(KeyStoreLoadingStrategy.LOAD_INPUT_STREAM));

            try {
                this.resource = resourceLoader.getResource(keystoreProperties.getLocation());
            } catch (Exception ex) {
                throw new RuntimeException("Error trying to get the keystore resource", ex);
            }

            assertThatResourceExists();
        }
    }

    private KeyStore instantiateKeystore() {
        KeyStore localKeystore = null;

        try {
            if (StringUtils.isNotBlank(keystoreProperties.getProvider())) {

                if (keystoreProperties.getType().equals("PKCS11")) {
                    configurePKCS11Provider();
                }

                if (Objects.nonNull(keystoreProperties.getExternalProviderClass())) {
                    configureExternalProvider();
                }

                localKeystore = KeyStore.getInstance(keystoreProperties.getType(), keystoreProperties.getProvider());
            } else {
                localKeystore = KeyStore.getInstance(keystoreProperties.getType());
            }
        } catch (KeyStoreException | NoSuchProviderException ex) {
            throw new RuntimeException("Error trying to instantiate the Keystore", ex);
        }

        return localKeystore;
    }

    private void configurePKCS11Provider() {
        final String provider = getKeyStoreProperties().getProvider();
        if (Security.getProvider(provider) == null) {
            if (StringUtils.isBlank(getKeyStoreProperties().getPkcs11().getConfigFilePath())) {
                throw new KeystoreInitializationException(
                    "When using PKCS11 keystore type pkcs11.configFilePath property is required");
            }

            final String configurableBaseProvider = getKeyStoreProperties().getPkcs11().getConfigurableBaseProvider();
            log.info("Configuring PKCS11 keystore {} provider, using the base provider {}", provider,
                configurableBaseProvider);

            final Provider configuredProvider =
                Security.getProvider(configurableBaseProvider).configure(getKeyStoreProperties().getPkcs11().getConfigFilePath());

            Security.addProvider(configuredProvider);
            log.info("Provider {} added to security", provider);
        } else {
            log.info("Provider {} already instantiated, skipping configuration.", provider);
        }
    }

    private void loadKeystore() {
        log.info("Loading keystore {} with configs {}", keystoreDescription, keystoreProperties);

        try {

            switch (keystoreProperties.getLoadStrategy()) {

                case DO_NOT_LOAD:
                    log.info("Avoiding to load the keystore.");
                    break;
                case LOAD_WITH_ONLY_PASSWORD:
                    log.info("Loading the keystore with only password value.");
                    keystore.load(null, keystoreProperties.getPasswordCharArray());
                    break;
                case LOAD_INPUT_STREAM:
                default:
                    loadFromIs(keystore);
            }
        } catch (NoSuchAlgorithmException | CertificateException | IOException e) {
            throw new RuntimeException("Error trying to load its contents", e);
        }
    }

    private void loadFromIs(KeyStore keystore) throws NoSuchAlgorithmException, CertificateException {
        log.info("Loading with InputStream keystore {}", keystoreDescription);
        assertThatResourceExists();
        Assert.notNull(keystoreProperties.getPasswordCharArray(),
            "when using the load strategy %s, the configuration of the keystore password is required"
                .formatted(KeyStoreLoadingStrategy.LOAD_INPUT_STREAM));

        loadMetadataFromCertificateFile();

        try (InputStream is = resource.getInputStream()) {
            keystore.load(is, keystoreProperties.getPasswordCharArray());
        } catch (IOException e) {
            throw new RuntimeException("Error trying to read its contents", e);
        }
    }

    private void loadMetadataFromCertificateFile() {
        if (isCloudResource()) {
            log.trace("Loading metadata keystore {} as cloud resource ", keystoreDescription);
            loadInformationCloudResource();
        } else {
            log.trace("Loading metadata keystore {} as default resource", keystoreDescription);
            loadInformationResourceDefault();
        }
    }

    private void loadInformationResourceDefault() {
        lastLoadingTime = getResourceLastModifiedTimestamp();
        lastCreationTime = getResourceCreationTimestamp();
    }

    private void loadInformationCloudResource() {
        log.trace("Loading metadata from keystore {} as cloud resource", keystoreDescription);
        lastLoadingTime = getResourceLastModifiedTimestamp();
    }

    private boolean isCloudResource() {
        try {
            getPath();
            return false;
        } catch (UnsupportedOperationException e) {
            log.trace("Loading keystore {} as cloud resource", keystoreDescription);
            return true;
        }
    }

    private Path getPath() {
        try {

            Path currentpath = Paths.get(resource.getFile().getAbsolutePath());
            log.trace("Get keystore {} file path: {} ", keystoreDescription, currentpath);
            return currentpath;
        } catch (IOException e) {
            throw new RuntimeException(ERROR_TRYING_TO_GET_FILE_BY_PATH, e);
        }
    }

    private long getResourceLastModifiedTimestamp() {
        try {
            long resp = resource.lastModified();
            log.trace("Loading last modified timestamp: {} keystore {}", resp, keystoreDescription);
            return resp;
        } catch (IOException e) {
            throw new RuntimeException("Error trying to read its last modified timestamp", e);
        }
    }

    private long getResourceCreationTimestamp() {
        try {

            Path path = getPath();
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            long resp = attrs.creationTime().toMillis();
            log.trace("Loading creation timestamp: {} keystore {}", resp, keystoreDescription);
            return resp;
        } catch (IOException e) {
            throw new RuntimeException(ERROR_TRYING_TO_READ_ITS_CREATION_TIMESTAMP, e);
        }
    }

    private void assertThatResourceExists() {
        Assert.isTrue(resource.exists(), "keystore does not exist");
    }

    private void configureExternalProvider(){
        if (Objects.nonNull(Security.getProvider(keystoreProperties.getProvider()))) {
            log.info("Provider {} already instantiated, skipping configuration.", keystoreProperties.getProvider());
            return;
        }

        final Class<?> providerClass = keystoreProperties.getExternalProviderClass();
        log.info("Configuring provider {} with class {} for keystore {}",
                 keystoreProperties.getProvider(), providerClass.getName(), keystoreDescription);
        final Constructor<?> zeroArgConstructor = getZeroArgConstructor(providerClass);

        try {
            final Object providerObject = zeroArgConstructor.newInstance();

            if (providerObject instanceof Provider provider) {
                Security.addProvider(provider);
                log.info("Provider class {} added to security", providerClass.getName());
            } else {
                throw new KeystoreInitializationException(
                    "Provider class %s for keystore %s must extend java.security.Provider"
                        .formatted(providerClass.getName(), keystoreDescription));
            }

        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new KeystoreInitializationException("Failed to instantiate provider class %s for keystore %s"
                .formatted(providerClass.getName(), keystoreDescription), e);
        }
    }

    private Constructor<?> getZeroArgConstructor(Class<?> providerClass) {
        final Constructor<?>[] constructors = providerClass.getConstructors();

        Constructor<?> zeroArgConstructor = null;
        for (Constructor<?> constructor : constructors) {
            if (constructor.getParameterCount() == 0) {
                zeroArgConstructor = constructor;
                break;
            }
        }

        if (zeroArgConstructor == null) {
            throw new KeystoreInitializationException(
                "Provider class %s for keystore %s must have a public zero-argument constructor"
                    .formatted(providerClass.getName(), keystoreDescription));
        }

        return zeroArgConstructor;
    }

    private boolean isNewCertificatedFileToUpdate() {
        boolean isValidLastModifiedTimeToUpdate = getResourceLastModifiedTimestamp() > lastLoadingTime;
        if (isCloudResource()) {
            return isValidLastModifiedTimeToUpdate;
        }
        boolean isValidCreationTimeToUpdate = getResourceCreationTimestamp() > lastCreationTime;
        return isValidLastModifiedTimeToUpdate || isValidCreationTimeToUpdate;
    }

}
