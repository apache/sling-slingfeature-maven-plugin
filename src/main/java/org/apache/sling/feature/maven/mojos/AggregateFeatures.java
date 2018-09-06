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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
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
    String classifier;

    @Parameter(required = true)
    List<FeatureConfig> features;

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
        File aggregatedFeaturesDir = new File(project.getBuild().getDirectory(), FeatureConstants.FEATURE_PROCESSED_LOCATION);
        aggregatedFeaturesDir.mkdirs();
        Map<ArtifactId, Feature> contextFeatures = readFeaturesFromDirectory(aggregatedFeaturesDir);

        Map<ArtifactId, Feature> featureMap = readFeatures(features, contextFeatures);

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

        try (FileWriter fileWriter = new FileWriter(new File(aggregatedFeaturesDir, classifier + ".json"))) {
            FeatureJSONWriter.write(fileWriter, result);
        } catch (IOException e) {
            throw new MojoExecutionException("Problem writing assembled feature", e);
        }
    }

    private Map<ArtifactId, Feature> readFeatures(Collection<FeatureConfig> featureConfigs, Map<ArtifactId, Feature> contextFeatures) throws MojoExecutionException {
        Map<ArtifactId, Feature> featureMap = new LinkedHashMap<>();

        try {
            for (FeatureConfig fc : featureConfigs) {
                if (fc.location != null) {
                    readFeaturesFromDirectory(fc, featureMap);
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
            Map<ArtifactId, Feature> contextFeatures) throws IOException {
        ArtifactId id = new ArtifactId(fc.groupId, fc.artifactId, fc.version, fc.classifier, fc.type);
        Feature f = contextFeatures.get(id);
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

    private void readFeaturesFromDirectory(FeatureConfig fc, Map<ArtifactId, Feature> featureMap) throws IOException {
        Map<String,Feature> readFeatures = new HashMap<>();
        Map<String,String> includes = new HashMap<>();
        Map<String,String> excludes = new HashMap<>();

        for (String inc : fc.includes) {
            includes.put(inc, convertGlobToRegex(inc));
        }
        for (String exc : fc.excludes) {
            excludes.put(exc, convertGlobToRegex(exc));
        }

        nextFile:
        for (File f : new File(fc.location).listFiles()) {
            // First check that it is allowed as part of the includes
            boolean matchesIncludes = fc.includes.size() == 0;

            for (Iterator<String> it = includes.values().iterator(); it.hasNext(); ) {
                String inc = it.next();
                if (f.getName().matches(inc)) {
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
                if (f.getName().matches(exc)) {
                    if (!isGlob(exc)) {
                        // Not a glob
                        it.remove();
                    }
                    continue nextFile;
                }
            }

            Feature feat = readFeatureFromFile(f);
            readFeatures.put(f.getName(), feat);
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
        readFeatures.values().stream().forEach(f -> featureMap.put(f.getId(), f));

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

    private Map<ArtifactId, Feature> readFeaturesFromDirectory(File directory) throws MojoExecutionException {
        Map<ArtifactId, Feature> m = new HashMap<>();
        FeatureConfig fc = new FeatureConfig();
        fc.setLocation(directory.getAbsolutePath());

        try {
            readFeaturesFromDirectory(fc, m);
        } catch (IOException e) {
            throw new MojoExecutionException("Problem reading feature", e);
        }

        return m;
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
