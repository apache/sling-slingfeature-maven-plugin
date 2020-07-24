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
package org.apache.sling.feature.maven.mojos.apis;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.repository.RepositorySystem;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.extension.apiregions.api.ApiRegion;
import org.apache.sling.feature.maven.mojos.apis.ApisJarContext.ArtifactInfo;

/**
 * Context for creating the api jars
 */
public class ApisUtil {

    /** Tag for source when using SCM info */
    public static final String SCM_TAG = "scm-tag";

    /** Alternative SCM location. */
    public static final String SCM_LOCATION = "scm-location";

    /** Alternative SCM encoding, default is UTF-8 */
    public static final String SCM_ENCODING = "scm-encoding";

    /** Alternative IDs for artifact dependencies. */
    public static final String API_IDS = "api-ids";

    /** Alternative IDS to a source artifact. */
    public static final String SCM_IDS = "source-ids";

    /** Alternative classifier for the source artifact. */
    public static final String SCM_CLASSIFIER = "source-classifier";

    /** Links for javadocs. */
    public static final String JAVADOC_LINKS = "javadoc-links";

    /** Additional artifacts for javadoc classpath */
    public static final String JAVADOC_CLASSPATH = "javadoc-classpath";

    /** Ignore packages for api generation */
    public static final String IGNORE_PACKAGES = "apis-ignore";

    public static List<ArtifactId> getSourceIds(final Artifact artifact) throws MojoExecutionException {
        final String val = artifact.getMetadata().get(SCM_IDS);
        if ( val != null ) {
            final List<ArtifactId> result = new ArrayList<>();
            for(final String v : val.split(",")) {
                try {
                    final ArtifactId sourceId = ArtifactId.parse(v.trim());
                    if ( sourceId.getClassifier() == null ) {
                        throw new MojoExecutionException("Metadata " + SCM_IDS + " must specify classifier for source artifacts : " + sourceId.toMvnId());
                    }
                    result.add(sourceId);
                } catch (final IllegalArgumentException iae) {
                    throw new MojoExecutionException("Wrong format for artifact id : " + v);
                }
            }
            return result;
        }
        return null;
    }

    public static List<ArtifactId> getApiIds(final Artifact artifact) throws MojoExecutionException {
        final String val = artifact.getMetadata().get(API_IDS);
        if ( val != null ) {
            final List<ArtifactId> result = new ArrayList<>();
            for(final String v : val.split(",")) {
                try {
                    final ArtifactId id = ArtifactId.parse(v.trim());
                    result.add(id);
                } catch (final IllegalArgumentException iae) {
                    throw new MojoExecutionException("Wrong format for artifact id : " + v);
                }
            }
            return result;
        }
        return null;
    }

    public static List<String> getJavadocLinks(final Artifact artifact) {
        final String val = artifact.getMetadata().get(JAVADOC_LINKS);
        if ( val != null ) {
            final List<String> result = new ArrayList<>();
            for(String v : val.split(",")) {
                v = v.trim();
                if ( v.endsWith("/") ) {
                    v = v.substring(0, v.length() - 1);
                }
                result.add(v);
            }
            return result;
        }
        return null;
    }

    public static void getPackageList(final String javadocUrl, final Set<String> linkedPackages,
            final Map<String, Set<String>> linkedPackagesMap) throws MojoExecutionException {
        Set<String> result = linkedPackagesMap.get(javadocUrl);
        if ( result == null ) {
            result = new HashSet<>();
            linkedPackagesMap.put(javadocUrl, result);
            try {
                final URL url = new URL(javadocUrl.concat("/package-list"));
                try (final LineNumberReader reader = new LineNumberReader(new InputStreamReader(url.openConnection().getInputStream(), StandardCharsets.UTF_8))) {
                    String line = null;
                    while ( (line = reader.readLine()) != null ) {
                        result.add(line.trim());
                    }
                }
            } catch (final IOException e) {
                throw new MojoExecutionException("Unable to find/read package-list at ".concat(javadocUrl), e);
            }
        }
        result.stream().forEach(v -> linkedPackages.add(v));
    }

    public static Set<String> getIgnoredPackages(final Artifact bundle) {
        final Set<String> result = new HashSet<>();
        final String ignore = bundle.getMetadata().get(ApisUtil.IGNORE_PACKAGES);
        if (ignore != null) {
            for(final String p : ignore.split(",")) {
                result.add(p.trim());
            }
        }
        return result;
    }

    /**
     * Validate that only one source metadata is set
     * @param artifact The artifact to check
     * @throws MojoExecutionException If metadata information is invalid
     */
    public static void validateSourceInfo(final Artifact artifact) throws MojoExecutionException {
        int count = 0;
        if ( artifact.getMetadata().get(ApisUtil.SCM_LOCATION) != null ) {
            count++;
        }
        if ( artifact.getMetadata().get(ApisUtil.SCM_CLASSIFIER) != null ) {
            count++;
        }
        if ( artifact.getMetadata().get(ApisUtil.SCM_IDS) != null ) {
            count++;
        }
        if ( count > 1 ) {
            throw new MojoExecutionException("Only one source configuration out of " + ApisUtil.SCM_CLASSIFIER + ", " + ApisUtil.SCM_IDS + " or " + ApisUtil.SCM_LOCATION + " is allowed for " + artifact.getId().toMvnId());
        }
    }

    /**
     * Build the classpath for javadoc
     * @throws MojoExecutionException
     */
    public static Set<String> getJavadocClassPath(final Log log, final RepositorySystem repositorySystem,
            final MavenSession mavenSession,
            final ApisJarContext ctx, final ApiRegion region) throws MojoExecutionException {
        // classpath
        final Set<String> classpath = new TreeSet<>(ctx.getJavadocClasspath());
        for(final ArtifactInfo info : ctx.getArtifactInfos(region, false)) {
            final String ids = info.getArtifact().getMetadata().get(ApisUtil.JAVADOC_CLASSPATH);
            if ( ids != null ) {
                for(final String s : ids.split(",")) {
                    try {
                        final ArtifactId cpId = ArtifactId.parse(s.trim());

                        classpath.addAll(buildJavadocClasspath(log, repositorySystem, mavenSession, cpId));
                    } catch ( final IllegalArgumentException iae) {
                        throw new MojoExecutionException("Invalid javadoc classpath artifact id " + s);
                    }
                }
            }
        }
        return classpath;
    }

    public static Set<String> buildJavadocClasspath(final Log log, final RepositorySystem repositorySystem,
            final MavenSession mavenSession,
            final ArtifactId artifactId)
            throws MojoExecutionException {
        final Set<String> javadocClasspath = new HashSet<>();
        log.debug("Retrieving " + artifactId + " and related dependencies...");

        org.apache.maven.artifact.Artifact toBeResolvedArtifact = repositorySystem.createArtifactWithClassifier(artifactId.getGroupId(),
                                                                                                                artifactId.getArtifactId(),
                                                                                                                artifactId.getVersion(),
                                                                                                                artifactId.getType(),
                                                                                                                artifactId.getClassifier());
        ArtifactResolutionRequest request = new ArtifactResolutionRequest()
                                            .setArtifact(toBeResolvedArtifact)
                                            .setServers(mavenSession.getRequest().getServers())
                                            .setMirrors(mavenSession.getRequest().getMirrors())
                                            .setProxies(mavenSession.getRequest().getProxies())
                                            .setLocalRepository(mavenSession.getLocalRepository())
                                            .setRemoteRepositories(mavenSession.getRequest().getRemoteRepositories())
                                            .setForceUpdate(false)
                                            .setResolveRoot(true)
                                            .setResolveTransitively(true)
                                            .setCollectionFilter(new ArtifactFilter() {
                                                    // artifact filter
                                                    @Override
                                                    public boolean include(org.apache.maven.artifact.Artifact artifact) {
                                                        if (org.apache.maven.artifact.Artifact.SCOPE_TEST.equals(artifact.getScope())) {
                                                            return false;
                                                        }
                                                        return true;
                                                    }});

        ArtifactResolutionResult result = repositorySystem.resolve(request);

        // we only log if debug is enabled
        if (!result.isSuccess() && log.isDebugEnabled()) {
            if (result.hasCircularDependencyExceptions()) {
                log.warn("Cyclic dependency errors detected:");
                reportWarningMessages(log, result.getCircularDependencyExceptions());
            }

            if (result.hasErrorArtifactExceptions()) {
                log.warn("Resolution errors detected:");
                reportWarningMessages(log, result.getErrorArtifactExceptions());
            }

            if (result.hasMetadataResolutionExceptions()) {
                log.warn("Metadata resolution errors detected:");
                reportWarningMessages(log, result.getMetadataResolutionExceptions());
            }

            if (result.hasMissingArtifacts()) {
                log.warn("Missing artifacts detected:");
                for (org.apache.maven.artifact.Artifact missingArtifact : result.getMissingArtifacts()) {
                    log.warn(" - " + missingArtifact.getId());
                }
            }

            if (result.hasExceptions()) {
                log.warn("Generic errors detected:");
                for (Exception exception : result.getExceptions()) {
                    log.warn(" - " + exception.getMessage());
                }
            }
        }

        for (org.apache.maven.artifact.Artifact resolvedArtifact : result.getArtifacts()) {
            if (resolvedArtifact.getFile() != null) {
                log.debug("Adding to javadoc classpath " + resolvedArtifact);
                javadocClasspath.add(resolvedArtifact.getFile().getAbsolutePath());
            } else {
                log.debug("Ignoring for javadoc classpath " + resolvedArtifact);
            }
        }

        return javadocClasspath;
    }

    private static <E extends ArtifactResolutionException> void reportWarningMessages(final Log log, final Collection<E> exceptions) {
        for (E exception : exceptions) {
            log.warn(" - "
                          + exception.getMessage()
                          + " ("
                          + exception.getArtifact().getId()
                          + ")");
        }
    }

}
