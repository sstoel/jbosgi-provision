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
import static org.osgi.framework.namespace.PackageNamespace.RESOLUTION_DYNAMIC;
import static org.osgi.framework.namespace.AbstractWiringNamespace.RESOLUTION_OPTIONAL;
import static org.osgi.resource.Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE;

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

import org.jboss.osgi.repository.XRepository;
import org.jboss.osgi.resolver.XCapability;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XResolveContext;
import org.jboss.osgi.resolver.XResolver;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResource.State;
import org.jboss.osgi.resolver.spi.AbstractEnvironment;
import org.osgi.framework.Version;
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
public class AbstractResourceProvisioner implements XResourceProvisioner {

    private final XResolver resolver;
    private final XRepository repository;

    public AbstractResourceProvisioner(XResolver resolver, XRepository repository) {
        this.resolver = resolver;
        this.repository = repository;
    }

    @Override
    public final XResolver getResolver() {
        return resolver;
    }

    @Override
    public final XRepository getRepository() {
        return repository;
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
        while (itres.hasNext()) {
            XResource res = itres.next();
            if (res.isAbstract()) {
                itres.remove();
            }
        }

        AbstractProvisionResult result = new AbstractProvisionResult(mapping, unstatisfied, resources);
        LOGGER.debugf("END findResources");
        LOGGER.debugf("  resources: %s", result.getResources());
        LOGGER.debugf("  unsatisfied: %s", result.getUnsatisfiedRequirements());

        // Sanity check that we can resolve all result resources
        List<XResource> mandatory = new ArrayList<XResource>();
        mandatory.addAll(resources);
        try {
            XResolveContext context = resolver.createResolveContext(envclone, mandatory, null);
            resolver.resolve(context).entrySet();
        } catch (ResolutionException ex) {
            LOGGER.cannotResolveResultResources(ex);
        }

        return result;
    }

    private void findResources(XEnvironment env, List<XResource> unresolved, Map<XRequirement, XResource> mapping, Set<XRequirement> unstatisfied,
            List<XResource> resources) {

        // Resolve the unsatisfied reqs in the environment
        resolveInEnvironment(env, unresolved, mapping, unstatisfied, resources);
        if (unstatisfied.isEmpty())
            return;

        boolean envModified = false;
        Set<XResource> installable = new HashSet<XResource>();

        LOGGER.debugf("Finding unsatisfied reqs");

        Iterator<XRequirement> itun = unstatisfied.iterator();
        while (itun.hasNext()) {
            XRequirement req = itun.next();
            String reqnamespace = req.getNamespace();

            // Ignore requirements that are already in the environment
            if (!env.findProviders(req).isEmpty()) {
                continue;
            }

            // Continue if we cannot find a provider for a given requirement
            XCapability cap = findProviderInRepository(req);
            if (cap == null) {
                continue;
            }

            // Convert a maven/module resource to it's associated target resource
            XIdentityCapability icap = cap.getResource().getIdentityCapability();
            String icaptype = (String) icap.getAttribute(XResource.CAPABILITY_TYPE_ATTRIBUTE);
            if (XResource.TYPE_ABSTRACT.equals(icaptype)) {
                if (getRequirementDelegate(icap, XResource.MAVEN_IDENTITY_NAMESPACE) != null) {
                    XRequirement mreq = getRequirementDelegate(icap, XResource.MAVEN_IDENTITY_NAMESPACE);
                    Iterator<Capability> capIt = repository.findProviders(mreq).iterator();
                    if (capIt.hasNext()) {
                        XCapability mcap = (XCapability) capIt.next();
                        icap = mcap.getResource().getIdentityCapability();
                    }
                } else if (getRequirementDelegate(icap, XResource.MODULE_IDENTITY_NAMESPACE) != null) {
                    XRequirement mreq = getRequirementDelegate(icap, XResource.MODULE_IDENTITY_NAMESPACE);
                    Iterator<Capability> capIt = repository.findProviders(mreq).iterator();
                    if (capIt.hasNext()) {
                        XCapability mcap = (XCapability) capIt.next();
                        icap = mcap.getResource().getIdentityCapability();
                    }
                }

                // Remove the abstract requirement
                itun.remove();

            } else if (XResource.MAVEN_IDENTITY_NAMESPACE.equals(reqnamespace)) {

                // Remove the maven requirement
                itun.remove();
            }

            installable.add(icap.getResource());
        }

        // Install the resources that match the unsatisfied reqs
        for (XResource res : installable) {
            if (!resources.contains(res)) {
                Collection<XRequirement> reqs = getRequirements(res, null);
                Iterator<XRequirement> itreqs = reqs.iterator();
                while (itreqs.hasNext()) {
                    XRequirement req = itreqs.next();
                    boolean dynamic = RESOLUTION_DYNAMIC.equals(req.getDirective(REQUIREMENT_RESOLUTION_DIRECTIVE));
                    boolean optional = RESOLUTION_OPTIONAL.equals(req.getDirective(REQUIREMENT_RESOLUTION_DIRECTIVE));
                    if (dynamic || optional || env.findProviders(req).size() > 0) {
                        itreqs.remove();
                    }
                }
                LOGGER.debugf("Adding %d unsatisfied reqs", reqs.size());
                unstatisfied.addAll(reqs);
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

    private Collection<XRequirement> getRequirements(XResource res, String[] namespaces) {
        Set<XRequirement> reqs = new HashSet<XRequirement>();
        if (namespaces != null) {
            for (String ns : namespaces) {
                for (Requirement req : res.getRequirements(ns)) {
                    reqs.add((XRequirement) req);
                }
            }
        } else {
            for (Requirement req : res.getRequirements(null)) {
                reqs.add((XRequirement) req);
            }
        }
        return reqs;
    }

    private XRequirement getRequirementDelegate(XIdentityCapability icap, String namespace) {
        List<Requirement> mreqs = icap.getResource().getRequirements(namespace);
        return (XRequirement) (mreqs.size() == 1 ? mreqs.get(0) : null);
    }

    private XCapability findProviderInRepository(XRequirement req) {

        // Find the providers in the repository
        LOGGER.debugf("Find in repository: %s", req);
        Collection<Capability> providers = repository.findProviders(req);

        // Remove abstract resources
        if (providers.size() > 1) {
            providers = new ArrayList<Capability>(providers);
            Iterator<Capability> itcap = providers.iterator();
            while (itcap.hasNext()) {
                XCapability cap = (XCapability) itcap.next();
                if (cap.getResource().isAbstract()) {
                    itcap.remove();
                }
            }
        }

        XCapability cap = null;
        if (providers.size() == 1) {
            cap = (XCapability) providers.iterator().next();
            LOGGER.debugf(" Found one: %s", cap);
        } else if (providers.size() > 1) {
            List<Capability> sorted = new ArrayList<Capability>(providers);
            Collections.sort(sorted, new Comparator<Capability>() {
                @Override
                public int compare(Capability cap1, Capability cap2) {
                    XIdentityCapability icap1 = ((XResource) cap1.getResource()).getIdentityCapability();
                    XIdentityCapability icap2 = ((XResource) cap2.getResource()).getIdentityCapability();
                    Version v1 = icap1.getVersion();
                    Version v2 = icap2.getVersion();
                    return v2.compareTo(v1);
                }
            });
            LOGGER.debugf(" Found multiple: %s", sorted);
            cap = (XCapability) sorted.get(0);
        } else {
            LOGGER.debugf(" Not found: %s", req);
        }
        return cap;
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
                        }
                    }
                }
            }
            unstatisfied.clear();
        } catch (ResolutionException ex) {
            for (Requirement req : ex.getUnresolvedRequirements()) {
                LOGGER.debugf(" unresolved: %s", req);
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
