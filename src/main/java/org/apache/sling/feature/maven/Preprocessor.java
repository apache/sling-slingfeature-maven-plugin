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
package org.apache.sling.feature.maven;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.builder.BuilderContext;
import org.apache.sling.feature.builder.FeatureBuilder;
import org.apache.sling.feature.builder.FeatureProvider;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.codehaus.plexus.logging.Logger;

/**
 * The processor processes all feature projects.
 */
public class Preprocessor {

    /**
     * Process the provided projects.
     * @param env The environment with all maven settings and projects
     */
    public void process(final Environment env) {
        for(final FeatureProjectInfo finfo : env.modelProjects.values()) {
            process(env, finfo, FeatureProjectConfig.getMainConfig(finfo));
            process(env, finfo, FeatureProjectConfig.getTestConfig(finfo));
            if ( FeatureConstants.PACKAGING_FEATURE.equals(finfo.project.getPackaging()) && finfo.features.isEmpty() ) {
                throw new RuntimeException("Feature project has no feature defined: " + finfo.project.getId());
            }

            ProjectHelper.storeProjectInfo(finfo);
        }
    }

    /**
     * Process a feature project.
     * This method is invoked twice, once for the main project and one for testing.
     *
     * @param env The environment with all maven settings and projects
     * @param info The project to process.
     * @param config The configuration for the project.
     */
    private void process(final Environment env,
            final FeatureProjectInfo info,
            final FeatureProjectConfig config) {
        if ( (config.isTestConfig() && info.testFeatureDone == true )
             || (!config.isTestConfig() && info.featureDone == true) ) {
            env.logger.debug("Return assembled " + config.getName() + " for " + info.project.getId());
            return;
        }

        // prevent recursion and multiple processing
        if ( config.isTestConfig() ) {
            info.testFeatureDone = true;
        } else {
            info.featureDone = true;
        }
        env.logger.debug("Processing " + config.getName() + " in project " + info.project.getId());

        // read project features
        final List<Feature> features = readProjectFeatures(env.logger, info.project, config);
        if ( config.isTestConfig() ) {
            info.testFeatures = features;
        } else {
            info.features = features;
        }
        if ( features.isEmpty() ) {
            env.logger.debug("No " + config.getName() + " found in project " + info.project.getId());
            return;
        }

        // process attachments (only for jar or bundle)
        if ( "jar".equals(info.project.getPackaging())
             || "bundle".equals(info.project.getPackaging())) {
            if ( config.isSkipAddJarToFeature() ) {
                env.logger.debug("Skip adding jar to " + config.getName());
            } else {
                if ( info.features.size() > 1 ) {
                    throw new RuntimeException("Jar can only be added if just one feature is defined in the project");
                }
                final Artifact jar = new Artifact(new ArtifactId(info.project.getGroupId(),
                        info.project.getArtifactId(),
                        info.project.getVersion(),
                        null,
                        "jar"));
                if ( config.getJarStartOrder() != null ) {
                    jar.setStartOrder(Integer.valueOf(config.getJarStartOrder()));
                }
                features.get(0).getBundles().add(jar);
            }
        }

        // assemble features
        final List<Feature> assembledFeatures = new ArrayList<>();
        for(final Feature f : (config.isTestConfig() ? info.testFeatures : info.features)) {
            final Feature assembledFeature = FeatureBuilder.assemble(f, new BuilderContext(this.createFeatureProvider(env,
                info,
                config.isTestConfig(),
                config.isSkipAddDependencies(),
                config.getScope(), null)));
            assembledFeatures.add(assembledFeature);
        }
        if ( config.isTestConfig() ) {
            info.assembledTestFeatures = assembledFeatures;
        } else {
            info.assembledFeatures = assembledFeatures;
        }

        if ( config.isSkipAddDependencies() ) {
            env.logger.debug("Not adding artifacts from features as dependencies");
        } else {
            for(final Feature f : assembledFeatures) {
                addDependenciesFromFeature(env, info, f, config.getScope());
            }
        }
    }

    private void scan(final List<File> files, final File dir) {
        for(final File f : dir.listFiles()) {
            if ( !f.getName().startsWith(".") ) {
                if ( f.isDirectory() ) {
                    scan(files, f);
                } else if ( f.getName().endsWith(".json") ) {
                    files.add(f);
                }
            }
        }
    }

    /**
     * Add all dependencies from the feature
     * @param env The environment
     * @param info The project info
     * @param assembledFeature The assembled feature for finding the artifacts.
     * @param scope The scope which the new dependencies should have
     */
    private void addDependenciesFromFeature(
            final Environment env,
            final FeatureProjectInfo info,
            final Feature assembledFeature,
            final String scope) {
        for(final org.apache.sling.feature.Artifact entry : assembledFeature.getBundles()) {
            final ArtifactId a = entry.getId();
            if ( a.getGroupId().equals(info.project.getGroupId())
                 && a.getArtifactId().equals(info.project.getArtifactId())
                 && a.getVersion().equals(info.project.getVersion()) ) {
                // skip artifact from the same project
                env.logger.debug("- skipping dependency " + a.toMvnId());
                continue;
            }

            env.logger.debug("- adding dependency " + a.toMvnId());
            final Dependency dep = ProjectHelper.toDependency(a, scope);
            info.project.getDependencies().add(dep);
        }
        for(final Extension ext : assembledFeature.getExtensions()) {
            if ( ext.getType() != ExtensionType.ARTIFACTS ) {
                continue;
            }
            for(final org.apache.sling.feature.Artifact art : ext.getArtifacts()) {
                final ArtifactId a = art.getId();
                if ( a.getGroupId().equals(info.project.getGroupId())
                     && a.getArtifactId().equals(info.project.getArtifactId())
                     && a.getVersion().equals(info.project.getVersion()) ) {
                    // skip artifact from the same project
                    env.logger.debug("- skipping dependency " + a.toMvnId());
                    continue;
                }
                env.logger.debug("- adding dependency " + a.toMvnId());
                final Dependency dep = ProjectHelper.toDependency(a, scope);
                info.project.getDependencies().add(dep);
            }
        }
    }

    /**
     * Read the features for a feature project.
     * The feature is either inlined in the pom or stored in a file in the project.
     *
     * @param logger The logger
     * @param project The current maven project
     * @param config The configuration
     * @return The feature or {@code null}
     */
    protected List<Feature> readProjectFeatures(
            final Logger logger,
            final MavenProject project,
            final FeatureProjectConfig config) {
        // feature files first:
        final File dir = new File(project.getBasedir(), config.getFeaturesDir());
        if ( dir.exists() ) {
            final List<Feature> featureList = new ArrayList<>();
            final List<File> files = new ArrayList<>();
            scan(files, dir);

            for(final File file : files) {
                final StringBuilder sb = new StringBuilder();
                try (final Reader reader = new FileReader(file)) {
                    final char[] buf = new char[4096];
                    int l = 0;

                    while (( l = reader.read(buf)) > 0 ) {
                        sb.append(buf, 0, l);
                    }
                } catch ( final IOException io) {
                    throw new RuntimeException("Unable to read feature " + file.getAbsolutePath(), io);
                }

                final String json = Substitution.replaceMavenVars(project, sb.toString());

                try (final Reader reader = new StringReader(json)) {
                    final Feature feature = FeatureJSONReader.read(reader, file.getAbsolutePath());

                    this.checkFeatureId(project, feature);

                    this.setProjectInfo(project, feature);
                    this.postProcessReadFeature(feature);
                    featureList.add(feature);

                } catch ( final IOException io) {
                    throw new RuntimeException("Unable to read feature " + file.getAbsolutePath(), io);
                }
            }

            return featureList;
        } else {
            logger.debug("Feature directory " + config.getFeaturesDir() + " does not exist in project " + project.getId());
            return Collections.emptyList();
        }
    }

    private void checkFeatureId(final MavenProject project, final Feature feature) {
        // check feature id
        if ( !project.getGroupId().equals(feature.getId().getGroupId()) ) {
            throw new RuntimeException("Wrong group id for feature. It should be " + project.getGroupId() + " but is " + feature.getId().getGroupId());
        }
        if ( !project.getArtifactId().equals(feature.getId().getArtifactId()) ) {
            throw new RuntimeException("Wrong artifact id for feature. It should be " + project.getArtifactId() + " but is " + feature.getId().getArtifactId());
        }
        if ( !project.getVersion().equals(feature.getId().getVersion()) ) {
            throw new RuntimeException("Wrong version for feature. It should be " + project.getVersion() + " but is " + feature.getId().getVersion());
        }
    }

    /**
     * Hook to post process the local feature
     * @param result The read feature
     * @return The post processed feature
     */
    protected Feature postProcessReadFeature(final Feature result)  {
        return result;
    }

    protected void setProjectInfo(final MavenProject project, final Feature feature) {
        // set title, description, vendor, license
        if ( feature.getTitle() == null ) {
            feature.setTitle(project.getName());
        }
        if ( feature.getDescription() == null ) {
            feature.setDescription(project.getDescription());
        }
        if ( feature.getVendor() == null && project.getOrganization() != null ) {
            feature.setVendor(project.getOrganization().getName());
        }
        if ( feature.getLicense() == null
             && project.getLicenses() != null
             && !project.getLicenses().isEmpty()) {
            final String license = project.getLicenses().stream()
                    .filter(l -> l.getName() != null )
                    .map(l -> l.getName())
                    .collect(Collectors.joining(", "));

            feature.setLicense(license);
        }
    }

    protected FeatureProvider createFeatureProvider(final Environment env,
            final FeatureProjectInfo info,
            final boolean isTest,
            final boolean skipAddDependencies,
            final String dependencyScope,
            final List<Feature> projectFeatures) {
        return new FeatureProvider() {

            @Override
            public Feature provide(final ArtifactId id) {

                final Dependency dep = ProjectHelper.toDependency(id, dependencyScope);
                if ( !skipAddDependencies ) {

                    env.logger.debug("- adding feature dependency " + id.toMvnId());
                    info.project.getDependencies().add(dep);
                }

                // if it's a project from the current reactor build, we can't resolve it right now
                final String key = id.getGroupId() + ":" + id.getArtifactId();
                final FeatureProjectInfo depProjectInfo = env.modelProjects.get(key);
                if ( depProjectInfo != null ) {
                    env.logger.debug("Found reactor " + id.getType() + " dependency to project: " + id);
                    // check if it is a feature project
                    final FeatureProjectInfo depInfo = depProjectInfo;
                    if ( isTest ) {
                        process(env, depInfo, FeatureProjectConfig.getTestConfig(depInfo));
                    } else {
                        process(env, depInfo, FeatureProjectConfig.getMainConfig(depInfo));
                    }
                    Feature found = null;
                    for(final Feature f : (isTest ? depInfo.assembledTestFeatures : depInfo.assembledFeatures)) {
                        if ( f.getId().equals(id) ) {
                            found = f;
                            break;
                        }
                    }

                    if ( isTest && found == null ) {
                        env.logger.error("Unable to get feature " + id.toMvnId() + " : Recursive test feature dependency list including project " + info.project);
                    } else if ( !isTest && found == null ) {
                        env.logger.error("Unable to get feature " + id.toMvnId() + " : Recursive feature dependency list including project " + info.project);
                    }
                    return found;
                } else {
                    env.logger.debug("Found external " + id.getType() + " dependency: " + id);

                    // "external" dependency, we can already resolve it
                    final File featureFile = ProjectHelper.getOrResolveArtifact(info.project, env.session, env.artifactHandlerManager, env.resolver, id).getFile();
                    try (final FileReader r = new FileReader(featureFile)) {
                        return FeatureJSONReader.read(r, featureFile.getAbsolutePath());
                    } catch ( final IOException ioe) {
                        env.logger.error("Unable to read feature file from " + featureFile, ioe);
                    }
                }

                return null;
            }
        };
    }
}
