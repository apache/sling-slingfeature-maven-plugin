/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.feature.maven.mojos;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.analyser.Analyser;
import org.apache.sling.feature.analyser.AnalyserResult;
import org.apache.sling.feature.builder.ArtifactProvider;
import org.apache.sling.feature.builder.FeatureProvider;
import org.apache.sling.feature.maven.ProjectHelper;
import org.apache.sling.feature.scanner.Scanner;

/**
 * Analyse the feature.
 */
@Mojo(name = "analyse-features",
        defaultPhase = LifecyclePhase.VERIFY,
      requiresDependencyResolution = ResolutionScope.TEST,
      threadSafe = true
    )
public class AnalyseFeaturesMojo extends AbstractIncludingFeatureMojo {

    /**
     * The scans for the analyser
     */
    @Parameter
    private List<Scan> scans;

    /**
     * The framework to use for analysing the feature models if not
     * contained in an execution-environment extension.
     */
    @Parameter
    private Dependency framework;

    /**
     * By default the warnings from the analysers are logged. Setting this to
     * false disables logging them.
     * @since 1.3.8
     */
    @Parameter(defaultValue = "true")
    private boolean logWarnings;

    @Parameter(defaultValue = "true", property = "failon.analyser.errors")
    private boolean failOnAnalyserErrors;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkPreconditions();
        List<Scan> list = scans;
        if (list == null || list.isEmpty()) {
            // use default configuration
            final Scan a = new Scan();
            a.setFilesInclude("**/*.*");
            list = Collections.singletonList(a);
        }

        getLog().debug(MessageUtils.buffer().a("Setting up the ").strong("Scanner").a("...").toString());
        Scanner scanner;
        try {
            scanner = new Scanner(getArtifactProvider());
        } catch (final IOException e) {
            throw new MojoExecutionException("A fatal error occurred while setting up the Scanner, see error cause:",
                    e);
        }
        getLog().debug(MessageUtils.buffer().strong("Scanner").a(" successfully set up").toString());

        FeatureProvider featureProvider = getFeatureProvider();

        final Map<Feature, List<AnalyserResult>> results = new LinkedHashMap<>();
        for (final Scan an : list) {
            try {
                Map<String, Map<String, String>> taskConfiguration = an.getTaskConfiguration();

                getLog().debug(MessageUtils.buffer().a("Setting up the ").strong("analyser")
                        .a(" with following configuration:").toString());
                getLog().debug(" * Task Configuration = " + taskConfiguration);
                Set<String> includedTasks = an.getIncludeTasks();
                if (includedTasks == null) {
                    // use defaults
                    includedTasks = new HashSet<>();
                    includedTasks.add("bundle-packages");
                    includedTasks.add("requirements-capabilities");
                    includedTasks.add("apis-jar");
                    includedTasks.add("repoinit");
                    if (an.getExcludeTasks() != null) {
                        includedTasks.removeAll(an.getExcludeTasks());
                        if (includedTasks.isEmpty()) {
                            includedTasks = null;
                        }
                    }
                }
                getLog().debug(" * Include Tasks = " + includedTasks);
                getLog().debug(" * Exclude Tasks = " + an.getExcludeTasks());
                final Analyser analyser = new Analyser(scanner, taskConfiguration, includedTasks, an.getExcludeTasks());
                getLog().debug(MessageUtils.buffer().strong("Analyser").a(" successfully set up").toString());

                getLog().debug("Retrieving Feature files...");
                final Collection<Feature> features = this.getSelectedFeatures(an).values();

                if (features.isEmpty()) {
                    getLog().debug(
                            "There are no associated feature files to current project, plugin execution will be skipped");
                    continue;
                } else {
                    getLog().debug("Starting analysis of features...");
                }

                for (final Feature f : features) {
                    try {
                        getLog().debug(MessageUtils.buffer().a("Analyzing feature ").strong(f.getId().toMvnId())
                                .a(" ...").toString());
                        Dependency fwk = an.getFramework();
                        if (fwk == null) {
                            fwk = this.framework;
                        }
                        final AnalyserResult result = analyser.analyse(f, ProjectHelper.toArtifactId(fwk), featureProvider);

                        if (!results.containsKey(f)) {
                            results.put(f, new ArrayList<>());
                        }
                        results.get(f).add(result);
                    } catch (Exception t) {
                        throw new MojoFailureException(
                                "Exception during analysing feature " + f.getId().toMvnId() + " : " + t.getMessage(),
                                t);
                    }
                }
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "A fatal error occurred while setting up the analyzer, see error cause:", e);
            } finally {
                getLog().debug("Features analysis complete");
            }
        }
        boolean hasErrors = false;
        for(final Map.Entry<Feature, List<AnalyserResult>> entry : results.entrySet()) {
            final Feature f = entry.getKey();
            final List<AnalyserResult> result = entry.getValue();

            List<String> warnings = result.stream().flatMap(r -> r.getWarnings().stream()).collect(Collectors.toList());
            List<String> errors = result.stream().flatMap(r -> r.getErrors().stream()).collect(Collectors.toList());

            if ( (warnings.isEmpty() || !logWarnings) && errors.isEmpty() ) {
                getLog().debug(MessageUtils.buffer().a("feature ").project(f.getId().toMvnId())
                        .a(" succesfully passed all analysis").toString());
            } else {
                final String message;
                if (!errors.isEmpty()) {
                    if (logWarnings && !warnings.isEmpty()) {
                        message ="errors and warnings";
                    } else {
                        message = "errors";
                    }
                    hasErrors = true;
                } else {
                    message = "warnings";
                }
                final String m = "Analyser detected "
                    .concat(message).concat(" on feature '").concat(f.getId().toMvnId()).concat("'.");
                if (hasErrors) {
                    getLog().error(m);
                } else {
                    getLog().warn(m);
                }
                if ( logWarnings ) {
                    for (final String msg : warnings) {
                        getLog().warn(msg);
                    }
                }
                 for (final String msg : errors) {
                    getLog().error(msg);
                }
            }
        }
        if (hasErrors) {
            if ( failOnAnalyserErrors ) {
                throw new MojoFailureException(
                    "One or more feature analyser(s) detected feature error(s), please read the plugin log for more details");
            }
            getLog().warn("Errors found during analyser run, but this plugin is configured to ignore errors and continue the build!");
        }
    }

    protected ArtifactProvider getArtifactProvider() {
        return new ArtifactProvider() {

            @Override
            public URL provide(final ArtifactId id) {
                try {
                    return ProjectHelper.getOrResolveArtifact(project, mavenSession, artifactHandlerManager, repoSystem, id).getFile().toURI().toURL();
                } catch (final MalformedURLException e) {
                    getLog().debug("Malformed url " + e.getMessage(), e);
                    // ignore
                    return null;
                }
            }
        };
    }

    protected FeatureProvider getFeatureProvider() {
        return new BaseFeatureProvider();
    }
}
