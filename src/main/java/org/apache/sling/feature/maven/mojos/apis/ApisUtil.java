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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

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
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.maven.mojos.apis.ApisJarContext.ArtifactInfo;
import org.apache.sling.feature.maven.mojos.apis.spi.Processor;
import org.apache.sling.feature.maven.mojos.selection.IncludeExcludeMatcher;

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

    public static void getPackageList(String javadocUrl, final Set<String> linkedPackages,
            final Map<String, Set<String>> linkedPackagesMap) throws MojoExecutionException {
        if ( javadocUrl.endsWith("/") ) {
            javadocUrl = javadocUrl.substring(0, javadocUrl.length() - 1);
        }
        Set<String> result = linkedPackagesMap.get(javadocUrl);
        if ( result == null ) {
            result = new HashSet<>();
            linkedPackagesMap.put(javadocUrl, result);
            final String urlString = javadocUrl.concat("/package-list");
            try {
		        final URL url = new URL(urlString);
                try (final LineNumberReader reader = new LineNumberReader(new InputStreamReader(url.openConnection().getInputStream(), StandardCharsets.UTF_8))) {
                    String line = null;
                    while ( (line = reader.readLine()) != null ) {
                        result.add(line.trim());
                    }
                }
            } catch (final IOException e) {
                throw new MojoExecutionException("Unable to find/read package-list at ".concat(urlString), e);
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
     *
     * @param log The log to use
     * @param repositorySystem The repository system to use
     * @param mavenSession The maven session to use
     * @param ctx The Apis Jar Context to use
     * @param regionName The region name to use
     * @throws MojoExecutionException When an invalid artifact ID is found
     * @return The computed javadoc classpath
     */
    public static Collection<String> getJavadocClassPath(final Log log,
            final RepositorySystem repositorySystem,
            final MavenSession mavenSession,
            final ApisJarContext ctx,
            final String regionName) throws MojoExecutionException {
        // classpath - reverse order to have highest versions first
        final Map<ArtifactId, String> classpathMapping = new TreeMap<>(Comparator.reverseOrder());
        classpathMapping.putAll(ctx.getJavadocClasspath());

        for(final ArtifactInfo info : ctx.getArtifactInfos(regionName, false)) {
            final String ids = info.getArtifact().getMetadata().get(ApisUtil.JAVADOC_CLASSPATH);
            if ( ids != null ) {
                for(final String s : ids.split(",")) {
                    try {
                        final ArtifactId cpId = ArtifactId.parse(s.trim());

                        classpathMapping.putAll(buildJavadocClasspath(log, repositorySystem, mavenSession, cpId));
                    } catch ( final IllegalArgumentException iae) {
                        throw new MojoExecutionException("Invalid javadoc classpath artifact id " + s);
                    }
                }
            }
        }

        // filter classpath using rules
        // remove
        if ( !ctx.getConfig().getJavadocClasspathRemovals().isEmpty()) {
            log.debug("Using javadoc classpath removal: ".concat(ctx.getConfig().getJavadocClasspathRemovals().toString()));
            final IncludeExcludeMatcher matcher = new IncludeExcludeMatcher(ctx.getConfig().getJavadocClasspathRemovals(), null, null, false);
            final Iterator<ArtifactId> iter = classpathMapping.keySet().iterator();
            while ( iter.hasNext() ) {
                final ArtifactId id = iter.next();
                if ( matcher.matches(id) != null ) {
                    log.debug("Removing from javadoc classpath: " + id.toMvnId());
                    iter.remove();
                }
            }
        }

        // highest
        if ( !ctx.getConfig().getJavadocClasspathHighestVersions() .isEmpty() ) {
            log.debug("Using javadoc classpath highest versions: ".concat(ctx.getConfig().getJavadocClasspathHighestVersions() .toString()));
            final IncludeExcludeMatcher matcher = new IncludeExcludeMatcher(ctx.getConfig().getJavadocClasspathHighestVersions() , null, null, false);
            final Map<ArtifactId, List<ArtifactId>> highest = new HashMap<>();
            for(final Map.Entry<ArtifactId, String> entry : classpathMapping.entrySet()) {
                if ( matcher.matches(entry.getKey()) != null ) {
                    final ArtifactId key = entry.getKey().changeVersion("0");
                    highest.computeIfAbsent(key, k -> new ArrayList<>()).add(entry.getKey());
                }
            }

            for(final List<ArtifactId> versions : highest.values()) {
                Collections.sort(versions, Comparator.reverseOrder());
                for(int i=1; i<versions.size();i++) {
                    final ArtifactId id = versions.get(i);
                    classpathMapping.remove(id);
                    log.debug("Removing from javadoc classpath: " + id.toMvnId());
                }
            }
        }

        // top
        final List<String> classpath;
        if ( !ctx.getConfig().getJavadocClasspathTops().isEmpty()) {
            log.debug("Using javadoc classpath tops: ".concat(ctx.getConfig().getJavadocClasspathTops().toString()));
            final IncludeExcludeMatcher matcher = new IncludeExcludeMatcher(ctx.getConfig().getJavadocClasspathTops(), null, null, false);
            final List<String> tops = new ArrayList<>();

            final Iterator<Map.Entry<ArtifactId, String>> iter = classpathMapping.entrySet().iterator();
            while ( iter.hasNext() ) {
                final Map.Entry<ArtifactId, String> entry = iter.next();
                if ( matcher.matches(entry.getKey())  != null ) {
                    tops.add(0, entry.getValue());
                    iter.remove();
                }
            }
            classpath = new ArrayList<>(classpathMapping.values());
            for(final String path : tops) {
                classpath.add(0, path);
            }
        } else {
            classpath = new ArrayList<>(classpathMapping.values());
        }

        if ( log.isDebugEnabled() ) {
            log.debug("------------------------------------------------------------------");
            log.debug("Javadoc classpath: ");
            for(final String cp : classpath) {
                log.debug("- " + cp);
            }
            log.debug("------------------------------------------------------------------");
        }

        return classpath;
    }

    public static Map<ArtifactId, String> buildJavadocClasspath(final Log log, final RepositorySystem repositorySystem,
            final MavenSession mavenSession,
            final ArtifactId artifactId)
            throws MojoExecutionException {
        final Map<ArtifactId, String> javadocClasspath = new HashMap<>();
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
                javadocClasspath.put(new ArtifactId(resolvedArtifact.getGroupId(), resolvedArtifact.getArtifactId(), resolvedArtifact.getVersion(), resolvedArtifact.getClassifier(), resolvedArtifact.getType()),
                        resolvedArtifact.getFile().getAbsolutePath());
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

    /**
     * Get the list of processors
     * @return The processors - might be empty
     */
    public static List<Processor> getProcessors() {
        final ServiceLoader<Processor> loader = ServiceLoader.load(Processor.class);

        final List<Processor> result = new ArrayList<>();
        for(final Processor p : loader) {
            result.add(p);
        }
        return result;
    }

    /**
     * Get all packages contained in the archive
     * @param ctx The generation context
     * @param file The archive to check
     * @param extension The extension to check for
     * @return A tuple of packages containing files with the extension and packages with files not having the extension
     * @throws MojoExecutionException If processing fails
     */
    public static Map.Entry<Set<String>, Set<String>> getPackages(final ApisJarContext ctx, final File file, final String extension)
            throws MojoExecutionException {
        final Set<String> packages = new TreeSet<>();
        final Set<String> otherPackages = new TreeSet<>();

        final Set<String> excludes = new HashSet<>();
        for(final String v : ctx.getConfig().getBundleResourceFolders()) {
            excludes.add(v.concat("/"));
        }

        try (final JarInputStream jis = new JarInputStream(new FileInputStream(file))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                if ( !entry.isDirectory() ) {
                    boolean exclude = false;
                    for(final String v : excludes) {
                        if ( entry.getName().startsWith(v)) {
                            exclude = true;
                            break;
                        }
                    }
                    if ( !exclude ) {
                        final int lastPos = entry.getName().lastIndexOf('/');
                        if (lastPos != -1) {
                            final String packageName = entry.getName().substring(0, lastPos).replace('/', '.');

                            if (entry.getName().endsWith(extension)) {
                                packages.add(packageName);
                            } else {
                                otherPackages.add(packageName);
                            }
                        }
                    }
                }
                jis.closeEntry();
            }
        } catch (final IOException ioe) {
            throw new MojoExecutionException("Unable to scan file " + file + " : " + ioe.getMessage());
        }

        otherPackages.removeAll(packages);

        return Collections.singletonMap(packages, otherPackages).entrySet().iterator().next();
    }

    /**
     * Get all artifacts from the configured extensions
     * @param context The context
     * @param regionName The name of the region
     * @return A list of artifacts, might be empty
     * @throws MojoExecutionException If processing fails or configuration is invalid
     */
    public static List<Artifact> getAdditionalJavadocArtifacts(final ApisJarContext context, final String regionName) throws MojoExecutionException {
        final List<Artifact> result = new ArrayList<>();
        for(final String extensionName : context.getConfig().getAdditionalJavadocExtensions(regionName)) {
            final Extension extension = context.getFeature().getExtensions().getByName(extensionName);
            if ( extension != null ) {
                if ( extension.getType() != ExtensionType.ARTIFACTS ) {
                    throw new MojoExecutionException("Extension " + extensionName + " must be of type artifacts.");
                }
                result.addAll(extension.getArtifacts());
            }
        }

        return result;
    }

    public static void writeSourceReport(final boolean write, final Log log, final File reportFile, final List<ArtifactInfo> infos) throws MojoExecutionException {
        if (write) {
            Collections.sort(infos, new Comparator<ArtifactInfo>(){

                @Override
                public int compare(ArtifactInfo o1, ArtifactInfo o2) {
                    return o1.getId().compareTo(o2.getId());
                }

            });
            final List<String> output = new ArrayList<>();
            for (final ArtifactInfo info : infos) {
                if (info.getSources().isEmpty()) {
                    output.add("- ".concat(info.getId().toMvnId()).concat(" : NO SOURCES FOUND"));
                } else {
                    output.add(
                            "- ".concat(info.getId().toMvnId()).concat(" : ").concat(info.getSources().toString()));
                }
            }
            if ( output.isEmpty() ) {
                output.add("NO SOURCES FOUND");
            }
            log.info("--------------------------------------------------------");
            log.info("Used sources:");
            log.info("--------------------------------------------------------");
            output.stream().forEach(msg -> log.info(msg));
            try {
                Files.write(reportFile.toPath(), output);
            } catch (final IOException e) {
                throw new MojoExecutionException("Unable to write " + reportFile, e);
            }
        } else {
            if ( reportFile.exists() ) {
                reportFile.delete();
            }
        }
    }
}
