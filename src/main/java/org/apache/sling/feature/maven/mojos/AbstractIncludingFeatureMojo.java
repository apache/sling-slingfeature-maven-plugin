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
package org.apache.sling.feature.maven.mojos;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.maven.ProjectHelper;
import org.codehaus.plexus.util.AbstractScanner;

public class AbstractIncludingFeatureMojo extends AbstractFeatureMojo {

    protected List<Feature> getSelectedFeatures(final FeatureSelectionConfig config) throws MojoExecutionException {
        final List<Feature> selection = new ArrayList<>();

        selectFeatureFiles(config, selection);

        selectFeatureClassifiers(config, selection);

        // TODO process artifacts

        return selection;
    }

    private void selectFeatureClassifiers(final FeatureSelectionConfig config, final List<Feature> selection)
            throws MojoExecutionException {
        final Map<String, Feature> projectFeatures = ProjectHelper.getAssembledFeatures(this.project);
        boolean includeAll = false;
        for (final String c : config.getFeatureClassifiers()) {
            if ("*".equals(c)) {
                includeAll = true;
            }
        }
        if (includeAll && config.getFeatureClassifiers().size() > 1) {
            throw new MojoExecutionException("Match all (*) and additional classifiers are specified.");
        }
        for (final Map.Entry<String, Feature> entry : projectFeatures.entrySet()) {
            final String classifier = entry.getValue().getId().getClassifier();
            boolean include = includeAll;
            if (!include) {
                for (final String c : config.getFeatureClassifiers()) {
                    if (c.trim().length() == 0 && classifier == null) {
                        include = true;
                    } else if (c.equals(classifier)) {
                        include = true;
                    }
                }
            }
            if (include) {
                selection.add(entry.getValue());
            }
        }
    }

    private void selectFeatureFiles(final FeatureSelectionConfig config, final List<Feature> selection)
            throws MojoExecutionException {
        final Map<String, Feature> projectFeatures = ProjectHelper.getAssembledFeatures(this.project);

        final String prefix = this.features.toPath().normalize().toFile().getAbsolutePath().concat(File.separator);
        if (config.getIncludes().isEmpty()) {
            final FeatureScanner scanner = new FeatureScanner(projectFeatures, prefix);
            if (!config.getExcludes().isEmpty()) {
                scanner.setExcludes(config.getExcludes().toArray(new String[config.getExcludes().size()]));
            }
            scanner.scan();
            selection.addAll(scanner.getIncluded().values());
        } else {
            for (final String include : config.getIncludes()) {
                final FeatureScanner scanner = new FeatureScanner(projectFeatures, prefix);
                if (!config.getExcludes().isEmpty()) {
                    scanner.setExcludes(config.getExcludes().toArray(new String[config.getExcludes().size()]));
                }
                scanner.setIncludes(new String[] { include });
                scanner.scan();

                if (!include.contains("*") && scanner.getIncluded().isEmpty()) {
                    throw new MojoExecutionException("FeatureInclude " + include + " not found.");
                }
                selection.addAll(scanner.getIncluded().values());
            }
        }
        if (!config.getExcludes().isEmpty()) {
            for (final String exclude : config.getExcludes()) {
                if (!exclude.contains("*")) {
                    final FeatureScanner scanner = new FeatureScanner(projectFeatures, prefix);
                    scanner.setIncludes(new String[] { exclude });
                    scanner.scan();
                    if (scanner.getIncluded().isEmpty()) {
                        throw new MojoExecutionException("FeatureExclude " + exclude + " not found.");
                    }
                }
            }
        }
    }

    public static class FeatureScanner extends AbstractScanner {

        private final Map<String, Feature> features;

        private final Map<ArtifactId, Feature> included = new LinkedHashMap<>();

        private final String prefix;

        public FeatureScanner(final Map<String, Feature> features, final String prefix) {
            this.features = features;
            this.prefix = prefix;
        }

        @Override
        public void scan() {
            setupDefaultFilters();
            setupMatchPatterns();

            for (Map.Entry<String, Feature> entry : features.entrySet()) {
                // skip aggregates
                if (ProjectHelper.isAggregate(entry.getKey())) {
                    continue;
                }
                final String name = entry.getKey().substring(prefix.length());
                final String[] tokenizedName = tokenizePathToString(name, File.separator);
                if (isIncluded(name, tokenizedName)) {
                    if (!isExcluded(name, tokenizedName)) {
                        included.put(entry.getValue().getId(), entry.getValue());
                    }
                }
            }
        }

        static String[] tokenizePathToString(String path, String separator) {
            List<String> ret = new ArrayList<>();
            StringTokenizer st = new StringTokenizer(path, separator);
            while (st.hasMoreTokens()) {
                ret.add(st.nextToken());
            }
            return ret.toArray(new String[ret.size()]);
        }

        public Map<ArtifactId, Feature> getIncluded() {
            return this.included;
        }

        @Override
        public String[] getIncludedFiles() {
            return null;
        }

        @Override
        public String[] getIncludedDirectories() {
            return null;
        }

        @Override
        public File getBasedir() {
            return null;
        }
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // TODO Auto-generated method stub

    }


}
