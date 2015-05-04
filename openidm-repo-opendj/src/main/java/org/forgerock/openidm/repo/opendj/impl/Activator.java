/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright © 2015 ForgeRock AS. All rights reserved.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */
package org.forgerock.openidm.repo.opendj.impl;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi bundle activator
 */
public class Activator implements BundleActivator {
    final static Logger logger = LoggerFactory.getLogger(Activator.class);
    
    public void start(BundleContext context) {
        logger.info("OpenDJ bundle starting");
        
        // TODO: Setup RepoBootService
        
        /*
        JsonValue repoConfig = ConfigBootstrapHelper.getRepoBootConfig("opendj", context);
         
        if (repoConfig != null) {

        } else {
            logger.debug("No OpenDJ configuration detected");
        }
        */
        logger.info("OpenDJ bundle started");
    }

    public void stop(BundleContext context) {
        logger.info("OpenDJ bundle stopped");
    }
}