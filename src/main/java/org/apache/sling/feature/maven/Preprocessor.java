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
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;
import javax.json.stream.JsonGenerator;

import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.io.DirectoryScanner;
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

            ProjectHelper.validateFeatureClassifiers(finfo.project);
        }
    }

    /**
     * Process a feature project.
     * This method is invoked twice, once for the main project and then for testing.
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
        readProjectFeatures(env.logger, info, config);
        if ( (config.isTestConfig() ? info.testFeatures : info.features).isEmpty() ) {
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
                // add to first feature
                (config.isTestConfig() ? info.testFeatures : info.features).values().iterator().next().getBundles().add(jar);
            }
        }

        // assemble features
        final Map<String, Feature> features = (config.isTestConfig() ? info.testFeatures : info.features);
        final Map<String, Feature> processFeatures = new HashMap<>(features);
        final Map<String, Feature> aggregatedFeatures = (config.isTestConfig() ? info.assembledTestFeatures : info.assembledFeatures);
        while ( aggregatedFeatures.size() < features.size() ) {
        	final int start = aggregatedFeatures.size();

        	final Iterator<Map.Entry<String, Feature>> iter = processFeatures.entrySet().iterator();
        	while ( iter.hasNext() ) {
        		final Map.Entry<String, Feature> entry = iter.next();
        		boolean process = false;
        		if ( entry.getValue().getInclude() == null ) {
        			// no include we can process
        			process = true;
        		} else {
        		    final ArtifactId include = entry.getValue().getInclude().getId();
        		    if ( !include.getGroupId().equals(info.project.getGroupId())
        		      || !include.getArtifactId().equals(info.project.getArtifactId())
        		      || !include.getVersion().equals(info.project.getVersion()) ) {
        		    	process = true;
        		    } else {
        		    	// same project
        		    	for(final Feature f : aggregatedFeatures.values()) {
        		    		if ( f.getId().equals(include) ) {
        		    			process = true;
        		    			break;
        		    		}
        		    	}
        		    }
        		}
        		if ( process ) {
        			iter.remove();
    	            final Feature assembledFeature = FeatureBuilder.assemble(entry.getValue(), new BuilderContext(this.createFeatureProvider(env,
    		                info,
    		                config.isTestConfig(),
    		                config.isSkipAddDependencies(),
    		                config.getScope(), null)));
    	            aggregatedFeatures.put(entry.getKey(), assembledFeature);
    	            break;
        		}
        	}
        	if ( aggregatedFeatures.size() == start ) {
        		throw new RuntimeException("Circular dependency in features in project " + info.project.getId());
        	}
        }

        if ( config.isSkipAddDependencies() ) {
            env.logger.debug("Not adding artifacts from features as dependencies");
        } else {
            for(final Feature f : (config.isTestConfig() ? info.assembledTestFeatures : info.assembledFeatures).values()) {
                addDependenciesFromFeature(env, info, f, config.getScope());
            }
        }
    }

    private void scan(final List<File> files, final File dir, final String includes, final String excludes) {
        final DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(dir);
        if ( includes != null ) {
            scanner.setIncludes(includes.split(","));
        }
        if ( excludes != null ) {
            scanner.setExcludes(excludes.split(","));
        }
        scanner.scan();
        for(final String f : scanner.getIncludedFiles()) {
            files.add(new File(dir, f));
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
            this.addDependency(env.logger, info.project, entry.getId(), scope);
        }
        for(final Extension ext : assembledFeature.getExtensions()) {
            if ( ext.getType() != ExtensionType.ARTIFACTS ) {
                continue;
            }
            for(final org.apache.sling.feature.Artifact art : ext.getArtifacts()) {
                this.addDependency(env.logger, info.project, art.getId(), scope);
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
    protected void readProjectFeatures(
            final Logger logger,
            final FeatureProjectInfo info,
            final FeatureProjectConfig config) {
        // feature files first:
        final File dir = new File(info.project.getBasedir(), config.getFeaturesDir());
        if ( dir.exists() ) {
            final List<File> files = new ArrayList<>();
            scan(files, dir, config.getIncludes(), config.getExcludes());

            for(final File file : files) {
            	logger.debug("Reading feature file " + file + " in project " + info.project.getId());
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

                final String json = preprocessFeature(logger, info, config, file, sb.toString());
                try (final Reader reader = new StringReader(json)) {
                    final Feature feature = FeatureJSONReader.read(reader, file.getAbsolutePath());

                    this.checkFeatureId(info.project, feature);

                    ProjectHelper.setFeatureInfo(info.project, feature);
                    this.postProcessReadFeature(feature);
                    (config.isTestConfig() ? info.testFeatures : info.features).put(file.toPath().normalize().toFile().getAbsolutePath(), feature);

                } catch ( final IOException io) {
                    throw new RuntimeException("Unable to read feature " + file.getAbsolutePath(), io);
                }
            }
        } else {
            logger.debug("Feature directory " + config.getFeaturesDir() + " does not exist in project " + info.project.getId());
        }
    }

	protected String preprocessFeature(final Logger logger, final FeatureProjectInfo info,
			final FeatureProjectConfig config, final File file, final String readJson) {
		String json = Substitution.replaceMavenVars(info.project, readJson);

		// check if "id" is set
		try (final JsonReader reader = Json.createReader(new StringReader(json)) ) {
			final JsonObject obj = reader.readObject();
			if ( !obj.containsKey("id") ) {
				final StringBuilder isb = new StringBuilder();
				isb.append(info.project.getGroupId());
				isb.append(':');
				isb.append(info.project.getArtifactId());
				isb.append(':');
				isb.append(FeatureConstants.PACKAGING_FEATURE);
				// if the feature is in the root of the configured directory
				// and the feature is named "feature.json"
				// and the feature is not a test feature, this is the main feature
				// which does not get a classifier
				if ( config.isTestConfig()
					 || !file.getName().equals("feature.json")
					 || !file.getParentFile().getAbsolutePath().equals(new File(info.project.getBasedir(), config.getFeaturesDir()).getAbsolutePath())) {
		    		isb.append(':');
			    	final int lastDot = file.getName().lastIndexOf('.');
				    isb.append(file.getName().substring(0, lastDot));
				}
			    isb.append(':');
		   		isb.append(info.project.getVersion());

		        final StringWriter writer = new StringWriter();

		        logger.debug("Generating id " + isb.toString() + " for feature file " + file);
		        try ( final JsonGenerator generator = Json.createGenerator(writer) ) {
		        	generator.writeStartObject();

		        	generator.write("id", isb.toString());

		        	for(final Map.Entry<String, JsonValue> entry : obj.entrySet()) {
		                generator.write(entry.getKey(), entry.getValue());
		        	}
		        	generator.writeEnd();
		        }

		        json = writer.toString();
		   	}
		}
		return json;
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


    protected FeatureProvider createFeatureProvider(final Environment env,
            final FeatureProjectInfo info,
            final boolean isTest,
            final boolean skipAddDependencies,
            final String dependencyScope,
            final List<Feature> projectFeatures) {
    	final String projectKey = info.project.getGroupId() + ":" + info.project.getArtifactId();
        return new FeatureProvider() {

        	private final Set<ArtifactId> processing = new HashSet<>();

            @Override
            public Feature provide(final ArtifactId id) {
                if ( processing.contains(id) ) {
                    env.logger.error("Unable to get feature " + id.toMvnId() + " : Recursive dependency list including project " + info.project);
                    return null;
                }
                processing.add(id);
                try {
                	// add feature to dependencies
	                if ( !skipAddDependencies ) {
	                    addDependency(env.logger, info.project, id, dependencyScope);
	                }

	                final String key = id.getGroupId() + ":" + id.getArtifactId();
	                if ( projectKey.equals(key) ) {
	                	// this is a feature from the current project
	                	final Feature found = findFeature(info, isTest, id);
	                    if ( found == null ) {
	                        env.logger.error("Unable to find included feature " + id.toMvnId() + " in project " + info.project);
	                        return null;
	                    }
	                    return found;
	                }
	                // if it's a project from the current reactor build, we can't resolve it right now
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
	                    final Feature found = findFeature(info, isTest, id);

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
                } finally {
                	processing.remove(id);
                }
            }
        };
    }

    private void addDependency(final Logger logger, final MavenProject project, final ArtifactId id, final String scope) {
        if ( id.getGroupId().equals(project.getGroupId())
             && id.getArtifactId().equals(project.getArtifactId())
             && id.getVersion().equals(project.getVersion()) ) {
            // skip artifact from the same project
            logger.debug("- skipping dependency " + id.toMvnId());
        } else {

			boolean found = false;
			for(final Dependency d : project.getDependencies()) {
				if ( d.getGroupId().equals(id.getGroupId()) && d.getArtifactId().equals(id.getArtifactId())) {
					if ( d.getVersion().equals(id.getVersion()) && d.getType().equals(id.getType())) {
						if ( d.getClassifier() == null && id.getClassifier() == null ) {
							found = true;
							break;
						}
						if ( d.getClassifier() != null && d.getClassifier().equals(id.getClassifier())) {
							found = true;
							break;
						}
					}
				}
			}
			if ( !found ) {
				logger.debug("- adding dependency " + id.toMvnId());
				final Dependency dep = ProjectHelper.toDependency(id, scope);
				project.getDependencies().add(dep);
			}
        }
    }

    private Feature findFeature(final FeatureProjectInfo info, final boolean isTest, final ArtifactId id) {
    	Feature found = findFeature(isTest ? info.assembledTestFeatures : info.assembledFeatures, id);
        if ( found == null ) {
        	if ( isTest ) {
        		found = findFeature(info.assembledFeatures, id);
        	}
        }
        return found;
    }

    private Feature findFeature(final Map<String, Feature> featureMap, final ArtifactId id) {
    	Feature found = null;
    	if ( featureMap != null ) {
            for(final Map.Entry<String, Feature> f : featureMap.entrySet()) {
                if ( f.getValue().getId().equals(id) ) {
                    found = f.getValue();
                    break;
                }
            }
    	}
		return found;
    }
}
