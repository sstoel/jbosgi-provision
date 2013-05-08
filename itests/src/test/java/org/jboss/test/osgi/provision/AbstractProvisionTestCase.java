package org.jboss.test.osgi.provision;
/*
 * #%L
 * JBossOSGi Provision: Integration Tests
 * %%
 * Copyright (C) 2013 JBoss by Red Hat
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.osgi.provision.ProvisionService;
import org.jboss.osgi.repository.RepositoryReader;
import org.jboss.osgi.repository.RepositoryStorage;
import org.jboss.osgi.repository.XPersistentRepository;
import org.jboss.osgi.repository.XRepository;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XResource;
import org.junit.Before;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * Test provision service access
 *
 * @author thomas.diesler@jboss.com
 * @since 07-May-2013
 */
public abstract class AbstractProvisionTestCase {

    private static final AtomicBoolean initialized = new AtomicBoolean();

    @Before
    public void setUp () throws Exception {
        if (initialized.compareAndSet(false, true)) {
            initializeRepository((XPersistentRepository) getRepository());
        }
    }

    protected void initializeRepository(XPersistentRepository repo) throws Exception {
        // remove all resources
        RepositoryStorage storage = repo.getRepositoryStorage();
        RepositoryReader reader = storage.getRepositoryReader();
        XResource resource = reader.nextResource();
        while (resource != null) {
            storage.removeResource(resource);
            resource = reader.nextResource();
        }
    }

    abstract BundleContext getBundleContext();

    protected XEnvironment getEnvironment() {
        BundleContext context = getBundleContext();
        ServiceReference<XEnvironment> sref = context.getServiceReference(XEnvironment.class);
        return sref != null ? context.getService(sref) : null;
    }

    protected XRepository getRepository() {
        BundleContext context = getBundleContext();
        ServiceReference<XRepository> sref = context.getServiceReference(XRepository.class);
        return sref != null ? context.getService(sref) : null;
    }

    protected ProvisionService getProvisionService() {
        BundleContext context = getBundleContext();
        ServiceReference<ProvisionService> sref = context.getServiceReference(ProvisionService.class);
        return sref != null ? context.getService(sref) : null;
    }
}
