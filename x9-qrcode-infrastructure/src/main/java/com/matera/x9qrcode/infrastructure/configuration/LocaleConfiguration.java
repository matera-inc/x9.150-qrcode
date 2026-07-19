/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

import java.util.Locale;
import java.util.TimeZone;

import static java.time.ZoneOffset.UTC;

@Configuration(proxyBeanMethods = false)
public class LocaleConfiguration implements InitializingBean {

    public static final TimeZone DEFAULT_TIME_ZONE = TimeZone.getTimeZone(UTC);

    @Bean
    public LocaleResolver localeResolver() {
        return new FixedLocaleResolver(Locale.ENGLISH);
    }

    @Override
    public void afterPropertiesSet() {
        TimeZone.setDefault(DEFAULT_TIME_ZONE);
    }

}
