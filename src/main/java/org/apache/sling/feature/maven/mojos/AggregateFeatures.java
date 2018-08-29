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
import org.apache.sling.feature.Include;
import org.apache.sling.feature.builder.BuilderContext;
import org.apache.sling.feature.builder.FeatureBuilder;
import org.apache.sling.feature.builder.FeatureProvider;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.feature.io.json.FeatureJSONWriter;
import org.apache.sling.feature.maven.FeatureConstants;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
        Map<ArtifactId, Feature> featureMap = readFeatures(features);

        ArtifactId newFeatureID = new ArtifactId(project.getGroupId(), project.getArtifactId(),
                project.getVersion(), classifier, FeatureConstants.PACKAGING_FEATURE);
        Feature newFeature = new Feature(newFeatureID);
        newFeature.getIncludes().addAll(
                featureMap.keySet().stream()
                .map(Include::new)
                .collect(Collectors.toList()));

        BuilderContext builderContext = new BuilderContext(new FeatureProvider() {
            @Override
            public Feature provide(ArtifactId id) {
                return featureMap.get(id);
            }
        }); //.add(handlers)
        Feature result = FeatureBuilder.assemble(newFeature, builderContext);

        File aggregatedFeaturesDir = new File(project.getBuild().getDirectory(), FeatureConstants.FEATURE_PROCESSED_LOCATION);
        aggregatedFeaturesDir.mkdirs();

        try (FileWriter fileWriter = new FileWriter(new File(aggregatedFeaturesDir, classifier + ".json"))) {
            FeatureJSONWriter.write(fileWriter, result);
        } catch (IOException e) {
            throw new MojoExecutionException("Problem writing assembled feature", e);
        }
    }

    private Map<ArtifactId, Feature> readFeatures(Collection<FeatureConfig> featureConfigs) throws MojoExecutionException {
        Map<ArtifactId, Feature> featureMap = new HashMap<>();

        try {
            for (FeatureConfig fc : featureConfigs) {
                if (fc.location != null) {
                    readFeaturesFromDirectory(fc, featureMap);
                } else if (fc.artifactId != null) {
                    readFeaturesFromArtifact(fc, featureMap);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Problem reading feature", e);
        }

        return featureMap;
    }

    private void readFeaturesFromArtifact(FeatureConfig fc, Map<ArtifactId, Feature> featureMap) throws IOException {
        Artifact art = repoSystem.createArtifactWithClassifier(
                fc.groupId, fc.artifactId, fc.version, fc.type, fc.classifier);

        ArtifactResolutionRequest resReq = new ArtifactResolutionRequest()
            .setArtifact(art)
            .setLocalRepository(localRepository)
            .setRemoteRepositories(remoteRepositories);
        artifactResolver.resolve(resReq);

        File artFile = art.getFile();
        readFeatureFromFile(artFile, featureMap);
    }

    private void readFeaturesFromDirectory(FeatureConfig fc, Map<ArtifactId, Feature> featureMap) throws IOException {
        nextFile:
        for (File f : new File(fc.location).listFiles()) {
            boolean matchesIncludes = fc.includes.size() == 0;
            for (String inc : fc.includes) {
                inc = convertGlobToRegex(inc);
                if (f.getName().matches(inc)) {
                    matchesIncludes = true;
                    break;
                }
            }

            if (!matchesIncludes)
                continue nextFile;

            for (String exc : fc.excludes) {
                exc = convertGlobToRegex(exc);
                if (f.getName().matches(exc)) {
                    continue nextFile;
                }
            }

            readFeatureFromFile(f, featureMap);
        }
    }

    private String convertGlobToRegex(String glob) {
        glob = glob.replace(".", "[.]");
        glob = glob.replace("*", ".*");
        return glob;
    }

    private void readFeatureFromFile(File f, Map<ArtifactId, Feature> featureMap) throws IOException {
        String content = new String(Files.readAllBytes(f.toPath()));
        content = Substitution.replaceMavenVars(project, content);
        Feature feat = FeatureJSONReader.read(new StringReader(content), null);
        featureMap.put(feat.getId(), feat);
    }

    public static class FeatureConfig {
        // If the configuration is a directory
        String location;
        Set<String> includes = new HashSet<>();
        Set<String> excludes = new HashSet<>();

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
