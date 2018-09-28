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
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Spliterator;
import java.util.Spliterators;
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
import org.apache.sling.feature.io.json.FeatureJSONWriter;
import org.apache.sling.feature.maven.FeatureConstants;
import org.apache.sling.feature.maven.ProjectHelper;
import org.apache.sling.feature.maven.Substitution;

/**
 * Aggregate multiple features into a single one.
 */
@Mojo(name = "aggregate-features",
    defaultPhase = LifecyclePhase.PACKAGE,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true
)
public class AggregateFeatures extends AbstractFeatureMojo {

    @Parameter(required = true)
    List<FeatureConfig> aggregates;

    @Parameter(required = true)
    String classifier;

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
        final Map<String, Feature> projectFeatures = ProjectHelper.getFeatures(this.project);
        Map<ArtifactId, Feature> contextFeatures = new HashMap<>();
        for(final Map.Entry<String, Feature> entry : projectFeatures.entrySet()) {
            contextFeatures.put(entry.getValue().getId(), entry.getValue());
        }

        Map<ArtifactId, Feature> featureMap = readFeatures(aggregates, projectFeatures);

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
                project.getVersion(), classifier, FeatureConstants.PACKAGING_FEATURE);
        Feature result = FeatureBuilder.assemble(newFeatureID, builderContext, featureMap.values().toArray(new Feature[] {}));

        // write the feature
        final File outputFile = new File(this.project.getBuild().getDirectory() + File.separatorChar + classifier + ".json");
        outputFile.getParentFile().mkdirs();

        try ( final Writer writer = new FileWriter(outputFile)) {
            FeatureJSONWriter.write(writer, result);
        } catch (final IOException e) {
            throw new MojoExecutionException("Unable to write feature to " + outputFile, e);
        }

        // attach it as an additional artifact
        projectHelper.attachArtifact(project, FeatureConstants.PACKAGING_FEATURE,
                classifier, outputFile);
    }

    private Map<ArtifactId, Feature> readFeatures(Collection<FeatureConfig> featureConfigs,
            Map<String, Feature> contextFeatures) throws MojoExecutionException {
        Map<ArtifactId, Feature> featureMap = new LinkedHashMap<>();

        try {
            for (FeatureConfig fc : featureConfigs) {
                if (fc.location != null) {
                    readFeaturesFromDirectory(fc, featureMap, contextFeatures);
                } else if (fc.artifactId != null) {
                    readFeatureFromMavenArtifact(fc, featureMap, contextFeatures);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Problem reading feature", e);
        }

        return featureMap;
    }

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

    private void readFeaturesFromDirectory(FeatureConfig fc, Map<ArtifactId, Feature> featureMap,  Map<String, Feature> contextFeatures) throws IOException {
        if ( fc.location.startsWith("/") || fc.location.startsWith(".") ) {
            throw new IOException("Invalid location: " + fc.location);
        }

        final File f = new File(this.project.getBasedir(), fc.location.replace('/', File.separatorChar).replace('\\', File.separatorChar));
        final String prefix = f.getAbsolutePath().concat(File.separator);
        final Map<String, Feature> candidates = new LinkedHashMap<>();
        for(final Map.Entry<String, Feature> entry : contextFeatures.entrySet()) {
            if ( entry.getKey().startsWith(prefix) ) {
                candidates.put(entry.getKey(), entry.getValue());
            }
        }

        Map<String,String> includes = new HashMap<>();
        Map<String,String> excludes = new HashMap<>();

        for (String inc : fc.includes) {
            includes.put(inc, convertGlobToRegex(inc));
        }
        for (String exc : fc.excludes) {
            excludes.put(exc, convertGlobToRegex(exc));
        }
        Map<String,Feature> readFeatures = new HashMap<>();

        nextFile:
        for (Map.Entry<String, Feature> entry : candidates.entrySet()) {
            final String fileName = new File(entry.getKey()).getName();

            // First check that it is allowed as part of the includes
            boolean matchesIncludes = fc.includes.size() == 0;

            for (Iterator<String> it = includes.values().iterator(); it.hasNext(); ) {
                String inc = it.next();
                if (fileName.matches(inc)) {
                    matchesIncludes = true;
                    if (!isGlob(inc)) {
                        // Not a glob
                        it.remove();
                    }
                    break;
                }
            }

            if (!matchesIncludes)
                continue nextFile;

            // Ensure there is no exclusion for it
            for (Iterator<String> it = excludes.values().iterator(); it.hasNext(); ) {
                String exc = it.next();
                if (fileName.matches(exc)) {
                    if (!isGlob(exc)) {
                        // Not a glob
                        it.remove();
                    }
                    continue nextFile;
                }
            }

            readFeatures.put(entry.getKey(), entry.getValue());
        }

        // Ordering:
        // put the read features in the main featureMap, order the non-globbed ones as specified in the plugin
        for (String inc : fc.includes) {
            Feature feat = readFeatures.remove(inc);
            if (feat != null) {
                featureMap.put(feat.getId(), feat);
            }
        }
        // Put all the remaining features on the map
        readFeatures.values().stream().forEach(v -> featureMap.put(v.getId(), v));

        // If there are any non-glob includes/excludes left, fail as the plugin is then incorrectly configured
        for (Map.Entry<String,String> i : includes.entrySet()) {
            if (!isGlob(i.getValue())) {
                throw new IOException("Non-wildcard include " + i.getKey() + " not found.");
            }
        }
        for (Map.Entry<String,String> e : excludes.entrySet()) {
            if (!isGlob(e.getValue())) {
                throw new IOException("Non-wildcard exclude " + e.getKey() + " not found.");
            }
        }
    }

    private boolean isGlob(String name) {
        return name.contains("*");
    }

    private String convertGlobToRegex(String glob) {
        glob = glob.replace(".", "[.]");
        glob = glob.replace("*", ".*");
        return glob;
    }

    private Feature readFeatureFromFile(File f) throws IOException {
        String content = new String(Files.readAllBytes(f.toPath()));
        content = Substitution.replaceMavenVars(project, content);
        return FeatureJSONReader.read(new StringReader(content), null);
    }

    public static class FeatureConfig {
        // If the configuration is a directory
        String location;
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();

        // If the configuration is an artifact
        String groupId;
        String artifactId;
        String version;
        String type;
        String classifier;

        public void setLocation(String loc) {
            location = loc;
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
            return "FeatureConfig [location=" + location + ", includes=" + includes + ", excludes=" + excludes + ", groupId="
                    + groupId + ", artifactId=" + artifactId + ", version=" + version + ", type=" + type + ", classifier="
                    + classifier + "]";
        }
    }
}
