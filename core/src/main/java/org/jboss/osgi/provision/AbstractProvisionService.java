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

import static org.jboss.osgi.provision.ProvisionLogger.LOGGER;
import static org.jboss.osgi.provision.ProvisionMessages.MESSAGES;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.jboss.osgi.repository.XPersistentRepository;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XIdentityRequirement;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XResolveContext;
import org.jboss.osgi.resolver.XResolver;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResource.State;
import org.jboss.osgi.resolver.spi.AbstractEnvironment;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.service.resolver.ResolutionException;

/**
 * The Provision Service
 *
 * @author thomas.diesler@jboss.com
 * @since 06-May-2013
 */
public class AbstractProvisionService implements ProvisionService {

    private final XResolver resolver;
    private final XPersistentRepository repository;

    public AbstractProvisionService(XResolver resolver, XPersistentRepository repository) {
        this.resolver = resolver;
        this.repository = repository;
    }

    @Override
    public final XResolver getResolver() {
        return resolver;
    }

    @Override
    public final XPersistentRepository getRepository() {
        return repository;
    }

    @Override
    public <T> List<T> installResources(List<XResource> resources) throws ProvisionException {
        throw new UnsupportedOperationException();
    }

    @Override
    public final ProvisionResult findResources(XEnvironment env, Set<XRequirement> reqs) {
        if (env == null)
            throw MESSAGES.illegalArgumentNull("env");
        if (reqs == null)
            throw MESSAGES.illegalArgumentNull("reqs");
        
        LOGGER.debugf("START findResources: %s", reqs);
        
        // Install the unresolved resources into the cloned environment
        List<XResource> unresolved = new ArrayList<XResource>();
        XEnvironment envclone = cloneEnvironment(env);
        for (XRequirement req : reqs) {
            XResource res = req.getResource();
            if (res.getState() != State.INSTALLED) {
                envclone.installResources(res);
                unresolved.add(res);
            }
        }
        
        // Find the resources in the cloned environment
        List<XResource> resources = new ArrayList<XResource>();
        Set<XRequirement> unstatisfied = new HashSet<XRequirement>(reqs);
        Map<XRequirement, XResource> mapping = new HashMap<XRequirement, XResource>();
        findResources(envclone, unresolved, mapping, unstatisfied, resources);
        
        // Remove abstract resources
        Iterator<XResource> itres = resources.iterator();
        while(itres.hasNext()) {
            XResource res = itres.next();
            if (res.isAbstract()) {
                itres.remove();
            }
        }
        
        AbstractProvisionResult result = new AbstractProvisionResult(mapping, unstatisfied, resources);
        LOGGER.debugf("END findResources");
        LOGGER.debugf("  resources: %s", result.getResources());
        LOGGER.debugf("  unsatisfied: %s", result.getUnsatisfiedRequirements());
        
        return result;
    }

    private void findResources(XEnvironment env, List<XResource> unresolved, Map<XRequirement, XResource> mapping, Set<XRequirement> unstatisfied, List<XResource> resources) {

        // Resolve the unsatisfied reqs in the environment
        resolveInEnvironment(env, unresolved, mapping, unstatisfied, resources);
        if (unstatisfied.isEmpty())
            return;

        // Find installable resources in the repository that match the unsatisfied reqs
        Set<XResource> installable = new HashSet<XResource>();
        Iterator<XRequirement> itun = unstatisfied.iterator();
        while (itun.hasNext()) {
            XRequirement req = itun.next();
            Collection<Capability> providers = repository.findProviders(req);
            if (providers.size() == 0) 
                continue;
            
            XResource resource;
            if (providers.size() == 1) {
                // Found exactly one provider
                XIdentityCapability icap = (XIdentityCapability) providers.iterator().next();
                resource = icap.getResource();
            } else {
                // Found multiple providers, prefer the one with the higest version
                List<XIdentityCapability> sorted = new ArrayList<XIdentityCapability>();
                for (Capability cap : providers) {
                    sorted.add((XIdentityCapability) cap);
                }
                Collections.sort(sorted, new Comparator<XIdentityCapability>() {
                    @Override
                    public int compare(XIdentityCapability cap1, XIdentityCapability cap2) {
                        Version v1 = cap1.getVersion();
                        Version v2 = cap2.getVersion();
                        return v2.compareTo(v1);
                    }
                });
                
                XIdentityCapability icap = sorted.get(0);
                resource = icap.getResource();
            }
            installable.add(resource);

            // Remove an unsatisfied maven requirement 
            if (XResource.MAVEN_IDENTITY_NAMESPACE.equals(req.getNamespace()))
                itun.remove();
        }

        // The namespaces for nested requirements that are added to the unsattisfied requirements
        String[] namespaces = new String[] { IdentityNamespace.IDENTITY_NAMESPACE, XResource.MAVEN_IDENTITY_NAMESPACE };
        boolean envModified = false;

        // Install the resources that match the unsatisfied reqs
        for (XResource res : installable) {
            if (!resources.contains(res)) {
                LOGGER.debugf("Found in repository: %s", res);
                unstatisfied.addAll(getRequirements(res, namespaces));
                env.installResources(res);
                resources.add(res);
                envModified = true;
            }
        }

        // Recursivly find the missing resources
        if (envModified) {
            findResources(env, unresolved, mapping, unstatisfied, resources);
        }
    }

    private Collection<XRequirement> getRequirements(XResource res, String... namespaces) {
        Set<XRequirement> reqs = new HashSet<XRequirement>();
        if (namespaces != null) {
            for (String ns : namespaces) {
                for (Requirement req : res.getRequirements(ns)) {
                    reqs.add((XIdentityRequirement) req);
                }
            }
        } else {
            for (Requirement req : res.getRequirements(null)) {
                reqs.add((XIdentityRequirement) req);
            }
        }
        return reqs;
    }

    private void resolveInEnvironment(XEnvironment env, List<XResource> unresolved, Map<XRequirement, XResource> mapping, Set<XRequirement> unstatisfied, List<XResource> resources) {
        List<XResource> mandatory = new ArrayList<XResource>();
        mandatory.addAll(unresolved);
        mandatory.addAll(resources);
        try {
            XResolveContext context = resolver.createResolveContext(env, mandatory, null);
            Set<Entry<Resource, List<Wire>>> wiremap = resolver.resolve(context).entrySet();
            for (Entry<Resource, List<Wire>> entry : wiremap) {
                Iterator<XRequirement> itunsat = unstatisfied.iterator();
                while (itunsat.hasNext()) {
                    XRequirement req = itunsat.next();
                    for (Wire wire : entry.getValue()) {
                        if (wire.getRequirement() == req) {
                            XResource provider = (XResource) wire.getProvider();
                            mapping.put(req, provider);
                            itunsat.remove();
                        }
                    }
                }
            }
        } catch (ResolutionException ex) {
            for (Requirement req : ex.getUnresolvedRequirements()) {
                LOGGER.debugf("Unresolved requirement: %s", req);
            }
        }
    }

    private XEnvironment cloneEnvironment(XEnvironment env) {
        if (env instanceof AbstractEnvironment) {
            return ((AbstractEnvironment) env).clone();
        } else {
            AbstractEnvironment clone = new AbstractEnvironment();
            Iterator<XResource> itres = env.getResources(null);
            while (itres.hasNext()) {
                clone.installResources(itres.next());
            }
            return clone;
        }
    }

    static class AbstractProvisionResult implements ProvisionResult {

        private final Map<XRequirement, XResource> mapping;
        private final Set<XRequirement> unsatisfied;
        private final List<XResource> resources;

        public AbstractProvisionResult(Map<XRequirement, XResource> mapping, Set<XRequirement> unstatisfied, List<XResource> resources) {
            this.mapping = mapping;
            this.unsatisfied = unstatisfied;
            this.resources = resources;
        }

        @Override
        public Map<XRequirement, XResource> getRequirementMapping() {
            return Collections.unmodifiableMap(mapping);
        }

        @Override
        public List<XResource> getResources() {
            return Collections.unmodifiableList(resources);
        }

        @Override
        public Set<XRequirement> getUnsatisfiedRequirements() {
            return Collections.unmodifiableSet(unsatisfied);
        }
    }
}
