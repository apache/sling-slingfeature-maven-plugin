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

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.StringTokenizer;
import java.util.stream.StreamSupport;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.repository.RepositorySystem;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.KeyValueMap;
import org.apache.sling.feature.builder.BuilderContext;
import org.apache.sling.feature.builder.FeatureBuilder;
import org.apache.sling.feature.builder.FeatureExtensionHandler;
import org.apache.sling.feature.builder.FeatureProvider;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.feature.maven.FeatureConstants;
import org.apache.sling.feature.maven.ProjectHelper;
import org.apache.sling.feature.maven.Substitution;
import org.codehaus.plexus.util.AbstractScanner;

/**
 * Aggregate multiple features into a single one.
 *
 * TODO - check that classifier is not clashing with an already used classifier from the read
 * files. We should also check if this mojo is configured several times, that different classifiers are configured.
 */
@Mojo(name = "aggregate-features",
    defaultPhase = LifecyclePhase.GENERATE_RESOURCES,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true
)
public class AggregateFeatures extends AbstractFeatureMojo {

    @Parameter(required = true)
    List<FeatureConfig> aggregates;

    @Parameter(required = true)
    String aggregateClassifier;

    @Parameter(required = false)
    Map<String,String> variables;

    @Parameter(required = false)
    Map<String,String> frameworkProperties;

    @Parameter(property = "project.remoteArtifactRepositories", readonly = true, required = true)
    List<ArtifactRepository> remoteRepositories;

    @Parameter(property = "localRepository", readonly = true, required = true)
    ArtifactRepository localRepository;

    @Component
    RepositorySystem repoSystem;

    @Component
    ArtifactResolver artifactResolver;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // get map of all project features
        final Map<String, Feature> projectFeatures = ProjectHelper.getFeatures(this.project);
        // ..and hash them by artifact id
        final Map<ArtifactId, Feature> contextFeatures = new HashMap<>();
        for(final Map.Entry<String, Feature> entry : projectFeatures.entrySet()) {
            contextFeatures.put(entry.getValue().getId(), entry.getValue());
        }

        // get the map of features for this aggregate
        final Map<ArtifactId, Feature> featureMap = readFeatures(aggregates, projectFeatures);

        KeyValueMap variableOverrides = new KeyValueMap();
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                variableOverrides.put(entry.getKey(), entry.getValue());
            }
        }
        BuilderContext builderContext = new BuilderContext(new FeatureProvider() {
            @Override
            public Feature provide(ArtifactId id) {
                Feature f = featureMap.get(id);
                if (f != null)
                    return f;

                // Check for the feature in the local context
                f = contextFeatures.get(id);
                if (f != null)
                    return f;

                // Finally, look the feature up via Maven's dependency mechanism
                try {
                    return readFeatureFromMavenArtifact(id.getGroupId(), id.getArtifactId(), id.getVersion(),
                            id.getType(), id.getClassifier());
                } catch (IOException e) {
                    throw new RuntimeException("Cannot find feature: " + id, e);
                }
            }
        }, variableOverrides, frameworkProperties)
            .add(StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                ServiceLoader.load(FeatureExtensionHandler.class).iterator(), Spliterator.ORDERED), false)
            .toArray(FeatureExtensionHandler[]::new));

        ArtifactId newFeatureID = new ArtifactId(project.getGroupId(), project.getArtifactId(),
                project.getVersion(), aggregateClassifier, FeatureConstants.PACKAGING_FEATURE);
        Feature result = FeatureBuilder.assemble(newFeatureID, builderContext, featureMap.values().toArray(new Feature[] {}));

        // Add feature to map of features
        final String key = ":aggregate:" + aggregateClassifier;
        projectFeatures.put(key, result);
        ProjectHelper.getAssembledFeatures(this.project).put(key, result);
    }

    private Map<ArtifactId, Feature> readFeatures(Collection<FeatureConfig> featureConfigs,
            Map<String, Feature> contextFeatures)
    throws MojoExecutionException {
        final Map<ArtifactId, Feature> featureMap = new LinkedHashMap<>();

        try {
            for (final FeatureConfig fc : featureConfigs) {
                if (fc.isDirectory()) {
                    readFeaturesFromDirectory(fc, featureMap, contextFeatures);
                } else if (fc.isArtifact()) {
                    readFeatureFromMavenArtifact(fc, featureMap, contextFeatures);
                } else {
                    throw new MojoExecutionException("Invalid aggregate configuration: " + fc);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Problem reading feature", e);
        }

        return featureMap;
    }

    // TODO this only works for projects not being part of the reactor build
    private void readFeatureFromMavenArtifact(FeatureConfig fc, Map<ArtifactId, Feature> featureMap,
            Map<String, Feature> contextFeatures) throws IOException {
        ArtifactId id = new ArtifactId(fc.groupId, fc.artifactId, fc.version, fc.classifier, fc.type);
        Feature f = null;
        for(final Feature c : contextFeatures.values()) {
            if ( c.getId().equals(id)) {
                f = c;
                break;
            }
        }
        if (f != null) {
            featureMap.put(id, f);
        } else {
            f = readFeatureFromMavenArtifact(id);
            if (f != null) {
                featureMap.put(id, f);
            }
        }
    }

    private Feature readFeatureFromMavenArtifact(ArtifactId id) throws IOException {
        return readFeatureFromMavenArtifact(id.getGroupId(), id.getArtifactId(), id.getVersion(), id.getType(), id.getClassifier());
    }

    private Feature readFeatureFromMavenArtifact(String groupId, String artifactId, String version, String type, String classifier) throws IOException {
        File artFile = resolveMavenArtifact(groupId, artifactId, version, type, classifier);
        return readFeatureFromFile(artFile);
    }

    private File resolveMavenArtifact(String groupId, String artifactId, String version, String type, String classifier) {
        Artifact art = repoSystem.createArtifactWithClassifier(
                groupId, artifactId, version, type, classifier);

        ArtifactResolutionRequest resReq = new ArtifactResolutionRequest()
            .setArtifact(art)
            .setLocalRepository(localRepository)
            .setRemoteRepositories(remoteRepositories);
        artifactResolver.resolve(resReq);

        File artFile = art.getFile();
        return artFile;
    }

    private void readFeaturesFromDirectory(FeatureConfig fc,
            Map<ArtifactId, Feature> featureMap,
            Map<String, Feature> contextFeatures) throws IOException {
        final String prefix = this.features.getAbsolutePath().concat(File.separator);
        final FeatureScanner scanner = new FeatureScanner(contextFeatures, prefix);
        if ( !fc.includes.isEmpty() ) {
            scanner.setIncludes(fc.includes.toArray(new String[fc.includes.size()]));
        }
        if ( !fc.excludes.isEmpty() ) {
            scanner.setExcludes(fc.excludes.toArray(new String[fc.excludes.size()]));
        }
        scanner.scan();

        featureMap.putAll(scanner.getIncluded());
    }

    private Feature readFeatureFromFile(File f) throws IOException {
        String content = new String(Files.readAllBytes(f.toPath()));
        content = Substitution.replaceMavenVars(project, content);
        return FeatureJSONReader.read(new StringReader(content), null);
    }

    public static class FeatureConfig {
        // If the configuration is a directory
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();

        // If the configuration is an artifact
        String groupId;
        String artifactId;
        String version;
        String type;
        String classifier;

        public boolean isDirectory() {
            return (!this.includes.isEmpty() || !this.excludes.isEmpty())
                 && groupId == null
                 && artifactId == null
                 && version == null
                 && type == null
                 && classifier == null;
        }

        public boolean isArtifact() {
            return this.includes.isEmpty() && this.excludes.isEmpty()
                    && this.groupId != null
                    && this.artifactId != null
                    && this.version != null;
        }

        public void setIncludes(String i) {
            includes.add(i);
        }

        public void setExcludes(String e) {
            excludes.add(e);
        }

        public void setGroupId(String gid) {
            groupId = gid;
        }

        public void setArtifactId(String aid) {
            artifactId = aid;
        }

        public void setVersion(String ver) {
            version = ver;
        }

        public void setType(String t) {
            type = t;
        }

        public void setClassifier(String clf) {
            classifier = clf;
        }

        @Override
        public String toString() {
            return "FeatureConfig [includes=" + includes + ", excludes=" + excludes + ", groupId="
                    + groupId + ", artifactId=" + artifactId + ", version=" + version + ", type=" + type + ", classifier="
                    + classifier + "]";
        }
    }

    public static class FeatureScanner extends AbstractScanner  {

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

            for ( Map.Entry<String, Feature> entry : features.entrySet() ) {
                final String name = entry.getKey().substring(prefix.length());
                final String[] tokenizedName =  tokenizePathToString( name, File.separator );
                if ( isIncluded( name, tokenizedName ) ) {
                   if ( !isExcluded( name, tokenizedName ) ) {
                       included.put( entry.getValue().getId(), entry.getValue() );
                   }
                }
            }
        }

        static String[] tokenizePathToString( String path, String separator )
        {
            List<String> ret = new ArrayList<>();
            StringTokenizer st = new StringTokenizer( path, separator );
            while ( st.hasMoreTokens() )
            {
                ret.add( st.nextToken() );
            }
            return ret.toArray( new String[ret.size()] );
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
}
