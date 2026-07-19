/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.keystore;

import com.matera.x9qrcode.app.service.KeystoreRetriever;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class KeystoreRetrieverUpdater {

	@Autowired
	private ApplicationEventPublisher applicationEventPublisher;

	@Autowired
	private Map<String, KeystoreRetriever> keystoreRetrieverMap;

	@Scheduled(fixedDelayString = "${keystore.reload.frequency:30000}")
	public void reloadKeystoreIfNeeded() {
		keystoreRetrieverMap.forEach((alias, keystoreRetriever) -> {
			log.debug("Checking if keystore {} needs reloading.", keystoreRetriever.getDescription());
			try {
				if (keystoreRetriever.reloadIfNotUpToDate()) {
					log.info("Keystore reloaded with alias {} and description {}", keystoreRetriever.getAliases(), keystoreRetriever.getDescription());
					applicationEventPublisher.publishEvent(KeystoreChangedEvent.builder().keystoreAlias(keystoreRetriever.getKeystoreAlias()));
				}
			} catch (Exception ex) {
				log.error("Error checking if keystore %s needed reloading".formatted(keystoreRetriever.getDescription()), ex);
			}
		});

	}

}
