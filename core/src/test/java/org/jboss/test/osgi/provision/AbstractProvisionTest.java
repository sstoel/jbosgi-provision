/*
 * #%L
 * JBossOSGi Provision Core
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

import org.jboss.osgi.provision.ProvisionService;
import org.jboss.osgi.repository.XPersistentRepository;
import org.jboss.osgi.repository.spi.AbstractPersistentRepository;
import org.jboss.osgi.repository.spi.MemoryRepositoryStorage;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XResolver;
import org.jboss.osgi.resolver.spi.AbstractEnvironment;
import org.jboss.osgi.resolver.spi.XResolverFactoryLocator;
import org.junit.Before;

/**
 * The abstract provisioner test.
 *
 * @author thomas.diesler@jboss.com
 * @since 06-May-2013
 */
public abstract class AbstractProvisionTest {

    ProvisionService provisionService;
    XEnvironment environment;

    @Before
    public void setUp() throws Exception {
        XResolver resolver = XResolverFactoryLocator.getResolverFactory().createResolver();
        XPersistentRepository repository = new AbstractPersistentRepository(new MemoryRepositoryStorage.Factory());
        provisionService = new ProvisionService.Factory().createProvisionService(resolver, repository);
        environment = new AbstractEnvironment();
    }

    public ProvisionService getProvisionService() {
        return provisionService;
    }

    public XEnvironment getEnvironment() {
        return environment;
    }
}
