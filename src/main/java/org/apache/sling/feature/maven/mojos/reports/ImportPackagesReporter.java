/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.feature.maven.mojos.reports;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.scanner.BundleDescriptor;
import org.apache.sling.feature.scanner.FeatureDescriptor;
import org.apache.sling.feature.scanner.PackageInfo;

public class ImportPackagesReporter implements Reporter {

    @Override
    public String getName() {
        return "imported-packages";
    }

    @Override
    public void generateReport(final ReportContext ctx) throws MojoExecutionException {
        for(final Feature feature : ctx.getFeatures()) {
            FeatureDescriptor fd;
            try {
                fd = ctx.getScanner().scan(feature);
            } catch (final IOException e) {
                throw new MojoExecutionException("Unable to scan feature " + e.getMessage(), e);
            }

            final List<String> importedPackages = this.getImportedPackages(fd);

            if ( !importedPackages.isEmpty() ) {
                ctx.addReport(fd.getFeature().getId().changeType("imports.txt").toMvnName(), importedPackages);
            }
        }
    }

    private List<String> getImportedPackages(final FeatureDescriptor fd) {
        final List<String> packages = new ArrayList<>();

        for (final BundleDescriptor bd : fd.getBundleDescriptors()) {
            for (final PackageInfo p : bd.getImportedPackages()) {
                String version = p.getVersion();
                if ( version == null ) {
                    version = "any";
                }
                if (p.isOptional()) {
                    version = version.concat(";optional");
                }
                String ext = "";
                if (isUsedInExportedPackages(bd, p)) {
                    ext = "    used-in-exports";
                }
                packages.add(p.getName().concat("    ").concat(version).concat("    ").concat(bd.getArtifact().getId().toMvnId()).concat(ext));
            }
        }

        Collections.sort(packages);
        return packages;
    }

    private boolean isUsedInExportedPackages(final BundleDescriptor bd, final PackageInfo p) {
        for(final PackageInfo exportedPackage : bd.getExportedPackages()) {
            if ( exportedPackage.getUses().contains(p.getName()) ) {
                return true;
            }
        }
        return false;
    }
    
}
