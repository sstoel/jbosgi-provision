/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.osgi.provision;

import static org.jboss.osgi.provision.ProvisionLogger.LOGGER;
import static org.jboss.osgi.provision.ProvisionMessages.MESSAGES;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.osgi.repository.RepositoryReader;
import org.jboss.osgi.repository.RepositoryStorage;
import org.jboss.osgi.repository.RepositoryXMLReader;
import org.jboss.osgi.repository.ResourceInstaller;
import org.jboss.osgi.repository.XRepository;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XRequirementBuilder;
import org.jboss.osgi.resolver.XResource;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * @author Thomas.Diesler@jboss.com
 * @since 10-May-2013
 */
public class ProvisionerSupport {

    private final BundleContext syscontext;
    private final ResourceInstaller installer;
    private final XResourceProvisioner provisioner;
    private final XEnvironment environment;

    public interface ResourceHandle {

        <T> T adapt(Class<T> type);

        void uninstall();
    }

    public ProvisionerSupport(BundleContext syscontext) {
        this.syscontext = syscontext;
        this.installer = syscontext.getService(syscontext.getServiceReference(ResourceInstaller.class));
        this.provisioner = syscontext.getService(syscontext.getServiceReference(XResourceProvisioner.class));
        this.environment = syscontext.getService(syscontext.getServiceReference(XEnvironment.class));
    }

    public XEnvironment getEnvironment() {
        return environment;
    }

    public XResourceProvisioner getResourceProvisioner() {
        return provisioner;
    }

    public XRepository getRepository() {
        return provisioner.getRepository();
    }

    public List<ResourceHandle> installCapabilities(String namespace, String... features) throws Exception {
        if (namespace == null)
            throw MESSAGES.illegalArgumentNull("namespace");
        if (features == null)
            throw MESSAGES.illegalArgumentNull("features");

        XRequirement[] reqs = new XRequirement[features.length];
        for (int i=0; i < features.length; i++) {
            reqs[i] = XRequirementBuilder.create(namespace, features[i]).getRequirement();
        }
        return installCapabilities(reqs);
    }

    public List<ResourceHandle> installCapabilities(XRequirement... reqs) throws Exception {
        if (reqs == null)
            throw MESSAGES.illegalArgumentNull("reqs");

        // Obtain provisioner result
        ProvisionResult result = provisioner.findResources(environment, new HashSet<XRequirement>(Arrays.asList(reqs)));
        Set<XRequirement> unsat = result.getUnsatisfiedRequirements();
        if (!unsat.isEmpty())
            throw MESSAGES.unsatiesfiedRequirements(unsat);

        // Install the provision result
        List<ResourceHandle> reshandles = new ArrayList<ResourceHandle>();
        for (XResource res : result.getResources()) {
            final Bundle bundle = installer.installResource(syscontext, res);
            ResourceHandle handle = new ResourceHandle() {

                @Override
                @SuppressWarnings("unchecked")
                public <T> T adapt(Class<T> type) {
                    return (T) (type == Bundle.class ? bundle : null);
                }

                @Override
                public void uninstall() {
                    try {
                        bundle.uninstall();
                    } catch (Exception ex) {
                        LOGGER.warnf(ex, "Cannot uninstall bundle: %s", bundle);
                    }
                }
            };

            reshandles.add(handle);
        }
        // Start the provisioned bundles
        for (ResourceHandle handle : reshandles) {
            Bundle bundle = handle.adapt(Bundle.class);
            bundle.start();
        }
        return reshandles;
    }

    public void populateRepository(String... features) throws IOException {
        if (features == null)
            throw MESSAGES.illegalArgumentNull("features");

        for (String feature : features) {
            URL resourceURL = getResource(feature + ".xml");
            if (resourceURL != null) {
                RepositoryReader reader = RepositoryXMLReader.create(resourceURL.openStream());
                XResource auxres = reader.nextResource();
                while (auxres != null) {
                    XIdentityCapability icap = auxres.getIdentityCapability();
                    String nsvalue = (String) icap.getAttribute(icap.getNamespace());
                    XRequirement ireq = XRequirementBuilder.create(icap.getNamespace(), nsvalue).getRequirement();
                    RepositoryStorage storage = getRepository().adapt(RepositoryStorage.class);
                    if (storage.findProviders(ireq).isEmpty()) {
                        storage.addResource(auxres);
                    }
                    auxres = reader.nextResource();
                }
            }
        }
    }

    private URL getResource(String resname) {
        return ProvisionerSupport.class.getResource("/repository/" + resname);
    }
}