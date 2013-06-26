/*
 * #%L
 * JBossOSGi Provision: Core
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
package org.jboss.test.osgi.provision;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import javax.xml.stream.XMLStreamException;

import org.jboss.osgi.framework.Constants;
import org.jboss.osgi.framework.spi.SystemPaths;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.metadata.OSGiMetaDataBuilder;
import org.jboss.osgi.provision.AbstractResourceProvisioner;
import org.jboss.osgi.provision.ProvisionException;
import org.jboss.osgi.provision.ProvisionResult;
import org.jboss.osgi.provision.XResourceProvisioner;
import org.jboss.osgi.repository.RepositoryReader;
import org.jboss.osgi.repository.RepositoryStorage;
import org.jboss.osgi.repository.RepositoryXMLReader;
import org.jboss.osgi.repository.XPersistentRepository;
import org.jboss.osgi.repository.spi.AbstractPersistentRepository;
import org.jboss.osgi.repository.spi.MavenIdentityRepository;
import org.jboss.osgi.repository.spi.MemoryRepositoryStorage;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XResolver;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResourceBuilder;
import org.jboss.osgi.resolver.XResourceBuilderFactory;
import org.jboss.osgi.resolver.spi.AbstractEnvironment;
import org.jboss.osgi.resolver.spi.AbstractResolver;
import org.junit.Before;
import org.osgi.framework.Version;

/**
 * The abstract provisioner test.
 *
 * @author thomas.diesler@jboss.com
 * @since 06-May-2013
 */
public abstract class AbstractProvisionerTest {

    AtomicLong installIndex = new AtomicLong();
    XPersistentRepository repository;
    XResourceProvisioner provisionService;
    XEnvironment environment;

    @Before
    public void setUp() throws Exception {
        environment = new AbstractEnvironment();
        XResolver resolver = new AbstractResolver();
        repository = new AbstractPersistentRepository(new MemoryRepositoryStorage.Factory());
        repository.addRepositoryDelegate(new MavenIdentityRepository());
        provisionService = new AbstractResourceProvisioner(resolver, repository);
    }

    XResourceProvisioner getProvisioner() {
        return provisionService;
    }

    XEnvironment getEnvironment() {
        return environment;
    }

    XPersistentRepository getRepository() {
        return repository;
    }

    ProvisionResult findResources(Set<XRequirement> reqs) {
        return getProvisioner().findResources(getEnvironment(), reqs);
    }

    void installResources(List<XResource> resources) throws ProvisionException {
        for (XResource res : resources) {
            environment.installResources(res);
        }
    }

    void setupFrameworkEnvironment() {
        OSGiMetaDataBuilder builder = OSGiMetaDataBuilder.createBuilder(Constants.SYSTEM_BUNDLE_SYMBOLICNAME, Version.emptyVersion);
        for (String packageSpec : SystemPaths.DEFAULT_SYSTEM_PACKAGES) {
            builder.addExportPackages(packageSpec);
        }
        for (String packageSpec : SystemPaths.DEFAULT_FRAMEWORK_PACKAGES) {
            builder.addExportPackages(packageSpec);
        }
        OSGiMetaData systemMetaData = builder.getOSGiMetaData();
        XResourceBuilder<XResource> factory = XResourceBuilderFactory.create();
        XResource systemResource = factory.loadFrom(systemMetaData).getResource();
        environment.installResources(systemResource);
    }

    void setupRepository(String config) throws XMLStreamException {
        RepositoryStorage storage = getRepository().adapt(RepositoryStorage.class);
        RepositoryReader reader = getRepositoryReader(config);
        XResource res = reader.nextResource();
        while (res != null) {
            storage.addResource(res);
            res = reader.nextResource();
        }
    }

    RepositoryReader getRepositoryReader(String config) throws XMLStreamException {
        InputStream input = getClass().getClassLoader().getResourceAsStream(config);
        return RepositoryXMLReader.create(input);
    }
}
