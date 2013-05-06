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

import java.util.Collection;

import junit.framework.Assert;

import org.jboss.osgi.provision.ProvisionService;
import org.jboss.osgi.repository.RepositoryStorage;
import org.jboss.osgi.repository.XPersistentRepository;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XResource;
import org.junit.Test;


/**
 * Test the {@link ProvisionService}.
 *
 * @author thomas.diesler@jboss.com
 * @since 06-May-2013
 */
public class ProvisionTestCase extends AbstractProvisionTest {
    
    @Test
    public void testEmptyEnvironment() {
        XEnvironment env = getEnvironment();
        Collection<XResource> resources = env.getResources(XEnvironment.ALL_IDENTITY_TYPES);
        Assert.assertTrue("Empty environment", resources.isEmpty());
    }

    @Test
    public void testEmptyRepository() {
        ProvisionService provision = getProvisionService();
        XPersistentRepository repository = provision.getRepository();
        RepositoryStorage storage = repository.getRepositoryStorage();
        XResource resource = storage.getRepositoryReader().nextResource();
        Assert.assertNull("Empty repository", resource);
    }
}
