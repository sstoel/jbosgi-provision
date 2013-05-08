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
package org.jboss.osgi.provision;

import java.util.Set;

import org.jboss.osgi.provision.core.ProvisionServiceImpl;
import org.jboss.osgi.repository.XPersistentRepository;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XResolver;
import org.jboss.osgi.resolver.XResource;

/**
 * The Provision Service
 *
 * @author thomas.diesler@jboss.com
 * @since 06-May-2013
 */
public interface ProvisionService {
    
    XResolver getResolver();
    
    XPersistentRepository getRepository();
    
    ProvisionResult findResources(XEnvironment env, Set<XRequirement> reqs);
    
    void installResources(XEnvironment env, Set<XResource> resources) throws ProvisionException;
    
    class Factory {
        public ProvisionService createProvisionService(XResolver resolver, XPersistentRepository repository) {
            return new ProvisionServiceImpl(resolver, repository);
        }
    }
}
