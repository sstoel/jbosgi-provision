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

import java.io.InputStream;
import java.util.List;
import java.util.Set;

import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.osgi.provision.ProvisionException;
import org.jboss.osgi.provision.ProvisionResult;
import org.jboss.osgi.provision.XResourceProvisioner;
import org.jboss.osgi.repository.RepositoryReader;
import org.jboss.osgi.repository.RepositoryStorage;
import org.jboss.osgi.repository.RepositoryXMLReader;
import org.jboss.osgi.repository.XPersistentRepository;
import org.jboss.osgi.repository.XRepository;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XResource;
import org.junit.Before;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * Test provision service access
 *
 * @author thomas.diesler@jboss.com
 * @since 07-May-2013
 */
public abstract class AbstractProvisionerIntegrationTest {

    @ArquillianResource
    BundleContext context;

    @Before
    public void setUp () throws Exception {
        initializeRepository();
    }

    void initializeRepository() throws Exception {
        // remove all resources
        RepositoryStorage storage = getRepository().getRepositoryStorage();
        RepositoryReader reader = storage.getRepositoryReader();
        XResource resource = reader.nextResource();
        while (resource != null) {
            storage.removeResource(resource);
            resource = reader.nextResource();
        }
        // add initial resources
        InputStream input = getClass().getResourceAsStream("/repository/repository.xml");
        reader = RepositoryXMLReader.create(input);
        resource = reader.nextResource();
        while (resource != null) {
            storage.addResource(resource);
            resource = reader.nextResource();
        }
    }

    XEnvironment getEnvironment() {
        ServiceReference<XEnvironment> sref = context.getServiceReference(XEnvironment.class);
        return sref != null ? context.getService(sref) : null;
    }

    XPersistentRepository getRepository() {
        ServiceReference<XRepository> sref = context.getServiceReference(XRepository.class);
        return (XPersistentRepository) (sref != null ? context.getService(sref) : null);
    }

    XResourceProvisioner getProvisionService() {
        ServiceReference<XResourceProvisioner> sref = context.getServiceReference(XResourceProvisioner.class);
        return (sref != null ? context.getService(sref) : null);
    }

    ProvisionResult findResources(Set<XRequirement> reqs) {
        return getProvisionService().findResources(getEnvironment(), reqs);
    }

    List<Bundle> installResources(ProvisionResult result) throws ProvisionException {
        return getProvisionService().installResources(result.getResources(), Bundle.class);
    }

}
