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
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.utils.io.DirectoryScanner;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.feature.maven.FeatureProjectConfig;

@Mojo(
    name = "collect-artifacts",
    requiresProject = false,
    threadSafe = true
)
public final class ArtifactsMojo extends AbstractRepositoryMojo {

    private final Pattern gavPattern = Pattern.compile("([^: ]+):([^: ]+)(:([^: ]*)(:([^: ]+))?)?:([^: ]+)");

    /**
     * A CSV list of Feature GAV.
     * Specifying this property, <code>includes</code> parameter will be overridden
     */
    @Parameter(property = "features")
    private String csvFeaturesGAV;

    @Override
    protected Collection<Feature> getFeatureFiles() {
        DirectoryScanner directoryScanner = new DirectoryScanner();
        directoryScanner.setBasedir(features);
        directoryScanner.setIncludes(FeatureProjectConfig.DEFAULT_FEATURE_INCLUDES);
        directoryScanner.scan();

        Collection<Feature> features = new LinkedList<>();

        for (String featureFileName : directoryScanner.getIncludedFiles()) {
            File featureFile = new File(this.features, featureFileName);
            try (FileReader reader = new FileReader(featureFile)) {
                Feature feature = FeatureJSONReader.read(reader, featureFile.getAbsolutePath());
                features.add(feature);
            } catch (IOException e) {
                getLog().warn("An error occurred while parsing "
                        + featureFile
                        + " Feature file, related bundles will not be included - please read the log to know the cause:", e);
            }
        }

        return features;
    }

    @Override
    protected List<Include> getIncludes() {
        List<Include> includes = new ArrayList<>();

        if (csvFeaturesGAV != null && !csvFeaturesGAV.isEmpty()) {

            StringTokenizer tokenizer = new StringTokenizer(csvFeaturesGAV, ",");
            while (tokenizer.hasMoreTokens()) {
                String gav = tokenizer.nextToken();
                Matcher gavMatcher = gavPattern.matcher(gav);

                if (!gavMatcher.matches()) {
                    getLog().warn("Wrong GAV coordinates "
                            + gav
                            + " specified on 'features' property, expected format is groupId:artifactId[:packaging[:classifier]]:version");
                }

                Include include = new Include();
                include.setGroupId(gavMatcher.group(1));
                include.setArtifactId(gavMatcher.group(2));
                include.setVersion(gavMatcher.group(7));
                include.setType(gavMatcher.group(4));
                include.setClassifier(gavMatcher.group(6));

                includes.add(include);
            }
        }

        return includes;
    }

}
