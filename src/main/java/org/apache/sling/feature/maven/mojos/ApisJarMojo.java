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
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.json.JsonArray;

import org.apache.commons.lang3.JavaVersion;
import org.apache.commons.lang3.SystemUtils;
import org.apache.felix.utils.manifest.Clause;
import org.apache.felix.utils.manifest.Parser;
import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.model.Model;
import org.apache.maven.model.Scm;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.ScmTag;
import org.apache.maven.scm.ScmVersion;
import org.apache.maven.scm.command.checkout.CheckOutScmResult;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.shared.utils.io.DirectoryScanner;
import org.apache.maven.shared.utils.io.FileUtils;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.Extensions;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.builder.ArtifactProvider;
import org.apache.sling.feature.extension.apiregions.api.ApiExport;
import org.apache.sling.feature.extension.apiregions.api.ApiRegion;
import org.apache.sling.feature.extension.apiregions.api.ApiRegions;
import org.apache.sling.feature.io.IOUtils;
import org.apache.sling.feature.maven.ProjectHelper;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.components.io.fileselectors.FileSelector;
import org.codehaus.plexus.components.io.fileselectors.IncludeExcludeFileSelector;
import org.osgi.framework.Constants;

/**
 * Generates the APIs JARs for each selected Feature file.
 */
@Mojo(name = "apis-jar",
    defaultPhase = LifecyclePhase.PACKAGE,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true
)
public class ApisJarMojo extends AbstractIncludingFeatureMojo implements ArtifactFilter {

    /** Alternative ID to a source artifact. */
    private static final String SCM_ID = "sourceId";

    private static final String SCM_TAG = "scm-tag";

    private static final String SCM_LOCATION = "scm-location";

    private static final String APIS = "apis";

    private static final String SOURCES = "sources";

    private static final String JAVADOC = "javadoc";

    private static final String JAR_TYPE = "jar";

    private static final String JAVA_EXTENSION = ".java";

    private static final String CND_EXTENSION = ".cnd";

    private static final String NON_ASCII_PATTERN = "[^\\p{ASCII}]";

    private static final String SPACE = " ";

    private static final String PROPERTY_FILTER = ApisJarMojo.class.getName() + ".filter";

    private static final String PROPERTY_CLAUSE = ApisJarMojo.class.getName() + ".clause";

    private static final String PROPERTY_BUNDLE = ApisJarMojo.class.getName() + ".bundle";

    /**
     * Select the features for api generation.
     */
    @Parameter
    private FeatureSelectionConfig selection;

    /**
     * Patterns identifying which resources to include from bundles
     */
    @Parameter
    private String[] includeResources;

    /**
     * Names of the regions to include, by default all regions are included.
     */
    @Parameter(defaultValue = "*")
    private Set<String> includeRegions;

    /**
     * Names of the regions to exclude, by default no regions is excluded.
     */
    @Parameter
    private Set<String> excludeRegions;

    @Parameter
    private String[] javadocLinks;

    @Parameter(defaultValue = "false")
    private boolean ignoreJavadocErrors;

    /**
     * If set to true and api jars are created for more than one region, then the
     * higher region only gets the difference to the lower region. If set to false
     * each api jar gets the full region information (duplicating information)
     */
    @Parameter(defaultValue = "true")
    private boolean incrementalApis;

    /**
     * Additional resources for the api jar
     */
    @Parameter
    private List<File> apiResources;

    /**
     * Additional resources for the api source jar
     */
    @Parameter
    private List<File> apiSourceResources;

    /**
     * Additional resources for the api javadoc jar
     */
    @Parameter
    private List<File> apiJavadocResources;

    /**
     * If enabled, the created api jars will be atttached to the project
     */
    @Parameter(defaultValue = "true")
    private boolean attachApiJars;

    /**
     * Mapping for an api region name to a user defined name
     */
    @Parameter
    private Map<String, String> apiRegionNameMappings;

    /**
     * Mapping for the feature classifier to a user defined name
     */
    @Parameter
    private Map<String, String> apiClassifierMappings;

    @Parameter(defaultValue = "${project.build.directory}/apis-jars", readonly = true)
    private File mainOutputDir;

    @Component(hint = "default")
    private ModelBuilder modelBuilder;

    @Component
    private ScmManager scmManager;

    @Component
    private ArchiverManager archiverManager;

    @Component
    private RepositorySystem repositorySystem;

    private final Pattern pomPropertiesPattern = Pattern.compile("META-INF/maven/[^/]+/[^/]+/pom.properties");

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkPreconditions();

        getLog().debug("Retrieving Feature files...");
        final Collection<Feature> features = this.getSelectedFeatures(selection).values();

        if (features.isEmpty()) {
            getLog().info(
                    "There are no assciated Feature files in the current project, plugin execution will be skipped");
        } else {
            getLog().debug("Starting APIs JARs creation...");

            final ArtifactProvider artifactProvider = this.createArtifactProvider();
            for (final Feature feature : features) {
                onFeature(artifactProvider, feature);
            }
        }
    }

    /**
     * Apply region name mapping if configured
     *
     * @param regionName The region name
     * @return The mapped name or the original name
     */
    private String mapApiRegionName(final String regionName) {
        if (this.apiRegionNameMappings != null && this.apiRegionNameMappings.containsKey(regionName)) {
            return this.apiRegionNameMappings.get(regionName);
        }
        return regionName;
    }

    /**
     * Apply classifier mapping if configured
     *
     * @param classifier The classifier
     * @return The mapped classifier or the original classifier
     */
    private String mapApClassifier(final String classifier) {
        if (this.apiClassifierMappings != null && this.apiClassifierMappings.containsKey(classifier)) {
            return this.apiClassifierMappings.get(classifier);
        }
        return classifier;
    }

    private ArtifactProvider createArtifactProvider() {
        return new ArtifactProvider() {

            @Override
            public URL provide(final ArtifactId id) {
                try {
                    return ProjectHelper.getOrResolveArtifact(project, mavenSession, artifactHandlerManager, artifactResolver, id).getFile().toURI().toURL();
                } catch (final Exception e) {
                    getLog().debug("Unable to provide artifact " + id.toMvnId() + " : " + e.getMessage(), e);
                    return null;
                }
            }
        };
    }

    /**
     * Check if the region is included
     *
     * @param name The region name
     * @return {@code true} if the region is included
     */
    private boolean isRegionIncluded(final String name) {
        boolean included = false;
        for (final String i : this.includeRegions) {
            if ("*".equals(i) || i.equals(name)) {
                included = true;
                break;
            }
        }
        if (included && this.excludeRegions != null) {
            for (final String e : this.excludeRegions) {
                if (name.equals(e)) {
                    included = false;
                    break;
                }
            }
        }

        return included;
    }

    /**
     * Get the api regions for a feature If the feature does not have an api region
     * an artificial global region is returned.
     *
     * @param feature The feature
     * @return The api regions or {@code null} if the feature is wrongly configured
     *         or all regions are excluded
     * @throws MojoExecutionException If an error occurs
     */
    private ApiRegions getApiRegions(final Feature feature) throws MojoExecutionException {
        ApiRegions regions = null;

        Extensions extensions = feature.getExtensions();
        Extension apiRegionsExtension = extensions.getByName(ApiRegions.EXTENSION_NAME);
        if (apiRegionsExtension != null) {
            if (apiRegionsExtension.getJSONStructure() == null) {
                getLog().info(
                        "Feature file " + feature.getId().toMvnId() + " declares an empty '" + ApiRegions.EXTENSION_NAME
                    + "' extension, no API JAR will be created");
            } else {
                try {
                    regions = ApiRegions
                            .parse((JsonArray) apiRegionsExtension.getJSONStructure());
                } catch (final IOException ioe) {
                    throw new MojoExecutionException(ioe.getMessage(), ioe);
                }

                // calculate all api-regions first, taking the inheritance in account
                final List<ApiRegion> toBeRemoved = new ArrayList<>();
                for (final ApiRegion r : regions.listRegions()) {
                    if (r.getParent() != null && !this.incrementalApis) {
                        for (final ApiExport exp : r.getParent().listExports()) {
                            r.add(exp);
                        }
                    }
                    if (!isRegionIncluded(r.getName())) {
                        getLog().debug("API Region " + r.getName()
                                    + " will not processed due to the configured include/exclude list");
                        toBeRemoved.add(r);
                    }
                }
                for (final ApiRegion r : toBeRemoved) {
                    regions.remove(r);
                }

                // prepare filter
                for (final ApiRegion r : regions.listRegions()) {
                    for (final ApiExport e : r.listExports()) {
                        e.getProperties().put(PROPERTY_FILTER, packageToScannerFiler(e.getName()));
                    }
                }

                if (regions.isEmpty()) {
                    getLog().info("Feature file " + feature.getId().toMvnId()
                            + " has no included api regions, no API JAR will be created");
                    regions = null;
                }
            }
        } else {
            regions = new ApiRegions();
            // create exports on the fly
            regions.add(new ApiRegion(ApiRegion.GLOBAL) {

                @Override
                public ApiExport getExportByName(final String name) {
                    ApiExport exp = super.getExportByName(name);
                    if (exp == null) {
                        exp = new ApiExport(name);
                        this.add(exp);
                    }
                    return exp;
                }
            });
        }

        return regions;
    }

    /**
     * Create api jars for a feature
     */
    private void onFeature(final ArtifactProvider artifactProvider, final Feature feature)
            throws MojoExecutionException {
        getLog().info(MessageUtils.buffer().a("Creating API JARs for Feature ").strong(feature.getId().toMvnId())
                .a(" ...").toString());

        final ApiRegions regions = getApiRegions(feature);
        if (regions == null) {
            // wrongly configured api regions - skip execution
            return;
        }

        if (!mainOutputDir.exists()) {
            mainOutputDir.mkdirs();
        }

        // deflated and source dirs can be shared
        final File deflatedBinDir = newDir(mainOutputDir, "deflated-bin");
        final File deflatedSourcesDir = newDir(mainOutputDir, "deflated-sources");
        final File checkedOutSourcesDir = newDir(mainOutputDir, "checkouts");

        // create an output directory per feature
        final File featureDir = new File(mainOutputDir, feature.getId().getArtifactId());

        final Set<String> javadocClasspath = new HashSet<>();

        // for each bundle included in the feature file:
        for (Artifact artifact : feature.getBundles()) {
            onArtifact(artifactProvider, artifact, null, regions, javadocClasspath, deflatedBinDir,
                    deflatedSourcesDir, checkedOutSourcesDir);
        }

        // recollect and package stuff
        for (ApiRegion apiRegion : regions.listRegions()) {
            File regionDir = new File(featureDir, apiRegion.getName());

            File apisDir = new File(regionDir, APIS);
            List<String> nodeTypes = recollect(featureDir, deflatedBinDir, apiRegion, apisDir);
            final File apiJar = createArchive(feature.getId(), apisDir, apiRegion, APIS, nodeTypes, this.apiResources);
            report(apiJar, APIS, apiRegion, "class");

            File sourcesDir = new File(regionDir, SOURCES);
            recollect(featureDir, deflatedSourcesDir, apiRegion, sourcesDir);
            final File sourceJar = createArchive(feature.getId(), sourcesDir, apiRegion, SOURCES, null,
                    this.apiSourceResources);
            report(sourceJar, SOURCES, apiRegion, "java");

            if (sourcesDir.list().length > 0) {
                File javadocsDir = new File(regionDir, JAVADOC);
                generateJavadoc(sourcesDir, javadocsDir, javadocClasspath);
                final File javadocJar = createArchive(feature.getId(), javadocsDir, apiRegion, JAVADOC, null,
                        this.apiJavadocResources);
                report(javadocJar, JAVADOC, apiRegion, "html");
            } else {
                getLog().warn("Javadoc JAR will NOT be generated - sources directory was empty!");
            }
        }

        getLog().info(MessageUtils.buffer().a("APIs JARs for Feature ").debug(feature.getId().toMvnId())
                .a(" succesfully created").toString());
    }

    private void report(final File jarFile, final String apiType, final ApiRegion apiRegion, final String extension) throws MojoExecutionException {
        final List<String> packages = getPackages(jarFile, extension);
        final List<ApiExport> missing = new ArrayList<>();
        for (final ApiExport exp : apiRegion.listExports()) {
            if (!packages.remove(exp.getName())) {
                missing.add(exp);
            }
        }
        if (missing.isEmpty() && packages.isEmpty()) {
            getLog().info("Verified " + apiType + " jar for region " + apiRegion.getName());
        } else {
            Collections.sort(missing);
            getLog().info(apiType + " jar for region " + apiRegion.getName() + " has errors:");
            for (final ApiExport m : missing) {
                getLog().info("- Missing package " + m.getName() + " from bundle(s) "
                        + m.getProperties().get(PROPERTY_BUNDLE));
            }
            for (final String m : packages) {
                getLog().info("- Wrong package " + m);
            }
        }
    }

    private File getArtifactFile(final ArtifactProvider artifactProvider, final ArtifactId artifactId)
            throws MojoExecutionException {
        final URL artifactURL = retrieve(artifactProvider, artifactId);
        if (artifactURL == null) {
            throw new MojoExecutionException("Unable to find artifact " + artifactId.toMvnId());
        }
        File bundleFile = null;
        try
        {
            bundleFile = IOUtils.getFileFromURL(artifactURL, true, this.getTmpDir());
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage());
        }

        return bundleFile;
    }

    private Manifest getManifest(final ArtifactId artifactId, final File bundleFile) throws MojoExecutionException {
        try (JarFile bundle = new JarFile(bundleFile)) {
            getLog().debug("Reading Manifest headers from bundle " + bundleFile);

            final Manifest manifest = bundle.getManifest();

            if (manifest == null) {
                throw new MojoExecutionException("Artifact + " + artifactId.toMvnId() + " does not  have a manifest.");
            }
            return manifest;
        } catch (final IOException e) {
            throw new MojoExecutionException("An error occurred while reading manifest from file " + bundleFile
                    + " for artifact " + artifactId.toMvnId(), e);
        }
    }

    private void onArtifact(final ArtifactProvider artifactProvider, Artifact artifact, Manifest wrappingBundleManifest,
            ApiRegions apiRegions, Set<String> javadocClasspath, File deflatedBinDir, File deflatedSourcesDir,
            File checkedOutSourcesDir) throws MojoExecutionException {
        final ArtifactId artifactId = artifact.getId();
        final File bundleFile = getArtifactFile(artifactProvider, artifactId);

        final Manifest manifest;
        if (wrappingBundleManifest == null) {
            manifest = getManifest(artifactId, bundleFile);
        } else {
            manifest = wrappingBundleManifest;
        }

        // check if the bundle is exporting packages?
        final Clause[] exportedPackages = this.getExportedPackages(manifest);
        if (exportedPackages.length > 0) {

            // calculate the exported versioned packages in the manifest file for each
            // region
            // and calculate the exported versioned packages in the manifest file for each
            // region
            if (computeExports(apiRegions, exportedPackages, artifactId)) {
                // get includes for deflating
                final String[] exportPackagesIncludes = computeExportPackageIncludes(exportedPackages);

                // deflate all bundles first, in order to copy APIs and resources later,
                // depending to the region
                final String[] exportedPackagesAndWrappedBundles = Stream
                        .concat(Stream.concat(Stream.of(exportPackagesIncludes), Stream.of("**/*.jar")),
                                Stream.of(includeResources))
                        .toArray(String[]::new);
                final File deflatedBundleDirectory = deflate(deflatedBinDir, bundleFile,
                        exportedPackagesAndWrappedBundles);
                // renaming potential name-collapsing resources
                renameResources(deflatedBundleDirectory, artifactId);

                // download sources
                downloadSources(artifactProvider, artifact, deflatedSourcesDir, checkedOutSourcesDir,
                        exportPackagesIncludes);

                // check if the bundle wraps other bundles
                if (wrappingBundleManifest == null) { // wrappers of wrappers do not exist
                    computeWrappedBundles(manifest, deflatedBundleDirectory, apiRegions, javadocClasspath,
                            deflatedBinDir, deflatedSourcesDir, checkedOutSourcesDir, artifactProvider);
                }
            }

            javadocClasspath.addAll(buildJavadocClasspath(artifactId));
        }
    }

    private void computeWrappedBundles(Manifest manifest,
                                       File deflatedBundleDirectory,
            ApiRegions apiRegions,
                                       Set<String> javadocClasspath,
                                       File deflatedBinDir,
                                       File deflatedSourcesDir,
            File checkedOutSourcesDir, final ArtifactProvider artifactProvider) throws MojoExecutionException {

        final String bundleClassPath = manifest.getMainAttributes().getValue(Constants.BUNDLE_CLASSPATH);
        if (bundleClassPath == null || bundleClassPath.isEmpty()) {
            return;
        }

        final StringTokenizer tokenizer = new StringTokenizer(bundleClassPath, ",");
        while (tokenizer.hasMoreTokens()) {
            final String jarName = tokenizer.nextToken();
            if (".".equals(jarName)) {
                continue;
            }

            final File wrappedJar = new File(deflatedBundleDirectory, jarName);
            getLog().debug("Processing wrapped bundle " + wrappedJar);

            final Properties properties = new Properties();

            try (JarFile jarFile = new JarFile(wrappedJar)) {
                Enumeration<JarEntry> jarEntries = jarFile.entries();
                while (jarEntries.hasMoreElements()) {
                    JarEntry jarEntry = jarEntries.nextElement();
                    if (!jarEntry.isDirectory()
                            && pomPropertiesPattern.matcher(jarEntry.getName()).matches()) {
                        getLog().debug("Loading Maven GAV from " + wrappedJar + '!' + jarEntry.getName());
                        properties.load(jarFile.getInputStream(jarEntry));
                    }
                }
            } catch (IOException e) {
                throw new MojoExecutionException("An error occurred while processing wrapped bundle " + wrappedJar, e);
            }

            if (properties.isEmpty()) {
                getLog().debug("No Maven GAV info attached to wrapped bundle " + wrappedJar + ", it will be ignored");
            } else {
                getLog().debug("Handling synthetic artifacts from Maven GAV: " + properties);

                String groupId = properties.getProperty("groupId");
                String artifactId = properties.getProperty("artifactId");
                String version = properties.getProperty("version");
                String classifier = properties.getProperty("classifier");
                if (classifier == null) {
                    classifier = inferClassifier(jarName, artifactId, version);
                }

                Artifact syntheticArtifact = new Artifact(
                        new ArtifactId(groupId, artifactId, version, classifier, null));
                onArtifact(artifactProvider, syntheticArtifact, manifest, apiRegions, javadocClasspath, deflatedBinDir,
                        deflatedSourcesDir, checkedOutSourcesDir);
            }
        }
    }

    // Guess the classifier based on the file name
    String inferClassifier(String bundleName, String artifactId, String version) {
        if (bundleName == null || artifactId == null || version == null)
            return null;

        int idx = bundleName.lastIndexOf('/');
        if (idx >= 0)
            bundleName = bundleName.substring(idx + 1);

        int edx = bundleName.lastIndexOf('.');
        if (edx > 0)
            bundleName = bundleName.substring(0, edx);

        // bundleName is now the bare name without extension
        String synthesized = artifactId + "-" + version;
        if (synthesized.length() < bundleName.length() &&
                bundleName.startsWith(synthesized)) {
            String suffix = bundleName.substring(synthesized.length());
            if (suffix.length() > 1 && suffix.startsWith("-")) {
                String classifier = suffix.substring(1);
                getLog().debug(
                        "Inferred classifier of '" + artifactId + ":" + version + "' to be '" + classifier + "'");
                return classifier;
            }
        }
        return null;
    }

    private Set<String> buildJavadocClasspath(ArtifactId artifactId)
            throws MojoExecutionException {
        final Set<String> javadocClasspath = new HashSet<>();
        getLog().debug("Retrieving " + artifactId + " and related dependencies...");

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
                                            .setCollectionFilter(this);

        ArtifactResolutionResult result = repositorySystem.resolve(request);

        if (!result.isSuccess()) {
            if (result.hasCircularDependencyExceptions()) {
                getLog().warn("Cyclic dependency errors detected:");
                reportWarningMessages(result.getCircularDependencyExceptions());
            }

            if (result.hasErrorArtifactExceptions()) {
                getLog().warn("Resolution errors detected:");
                reportWarningMessages(result.getErrorArtifactExceptions());
            }

            if (result.hasMetadataResolutionExceptions()) {
                getLog().warn("Metadata resolution errors detected:");
                reportWarningMessages(result.getMetadataResolutionExceptions());
            }

            if (result.hasMissingArtifacts()) {
                getLog().warn("Missing artifacts detected:");
                for (org.apache.maven.artifact.Artifact missingArtifact : result.getMissingArtifacts()) {
                    getLog().warn(" - " + missingArtifact.getId());
                }
            }

            if (result.hasExceptions()) {
                getLog().warn("Generic errors detected:");
                for (Exception exception : result.getExceptions()) {
                    getLog().warn(" - " + exception.getMessage());
                }
            }
        }

        for (org.apache.maven.artifact.Artifact resolvedArtifact : result.getArtifacts()) {
            if (resolvedArtifact.getFile() != null) {
                getLog().debug("Adding to javadoc classpath " + resolvedArtifact);
                javadocClasspath.add(resolvedArtifact.getFile().getAbsolutePath());
            } else {
                getLog().debug("Ignoring for javadoc classpath " + resolvedArtifact);
            }
        }

        return javadocClasspath;
    }

    private <E extends ArtifactResolutionException> void reportWarningMessages(Collection<E> exceptions) {
        for (E exception : exceptions) {
            getLog().warn(" - "
                          + exception.getMessage()
                          + " ("
                          + exception.getArtifact().getId()
                          + ")");
        }
    }

    private URL retrieve(final ArtifactProvider artifactProvider, final ArtifactId artifactId) {
        getLog().debug("Retrieving artifact " + artifactId + "...");
        URL sourceFile = artifactProvider.provide(artifactId);
        if (sourceFile != null) {
            getLog().debug("Artifact " + artifactId + " successfully retrieved : " + sourceFile);
        }
        return sourceFile;
    }

    private File deflate(File deflatedDir, File artifact, String...includes) throws MojoExecutionException {
        File destDirectory = new File(deflatedDir, artifact.getName());
        if (destDirectory.exists()) {
            getLog().debug("Bundle " + artifact.getName() + " already deflated");
        } else {
            getLog().debug("Deflating bundle " + artifact.getName() + "...");
            destDirectory.mkdirs();

            // unarchive the bundle
            try {
                UnArchiver unArchiver = archiverManager.getUnArchiver(artifact);
                unArchiver.setSourceFile(artifact);
                unArchiver.setDestDirectory(destDirectory);
                IncludeExcludeFileSelector selector = new IncludeExcludeFileSelector();
                selector.setIncludes(includes);
                selector.setExcludes(new String[] { "OSGI-OPT/**" });
                unArchiver.setFileSelectors(new FileSelector[] { selector });
                unArchiver.extract();
            } catch (NoSuchArchiverException e) {
                throw new MojoExecutionException(
                        "An error occurred while deflating file " + artifact + " to directory " + destDirectory, e);
            }

            getLog().debug("Bundle " + artifact + " successfully deflated");
        }
        return destDirectory;
    }

    private void renameResources(File destDirectory, ArtifactId artifactId) throws MojoExecutionException {
        if (includeResources == null || includeResources.length == 0) {
            getLog().debug("No configured resources to rename in " + destDirectory);
        }

        getLog().debug("Renaming " + Arrays.toString(includeResources) + " files in " + destDirectory + "...");

        DirectoryScanner directoryScanner = new DirectoryScanner();
        directoryScanner.setBasedir(destDirectory);
        directoryScanner.setIncludes(includeResources);
        directoryScanner.scan();

        if (directoryScanner.getIncludedFiles().length == 0) {
            getLog().debug("No " + Arrays.toString(includeResources) + " resources in " + destDirectory + " to be renamed renamed.");
            return;
        }

        for (String resourceName : directoryScanner.getIncludedFiles()) {
            final File resource = new File(destDirectory, resourceName);
            final String prefix = artifactId.getGroupId().concat("-").concat(artifactId.getArtifactId().concat("-"));

            if (resource.getName().startsWith(prefix)) {
                getLog().debug("No need to rename " + resource);
            } else {
                File renamed = new File(resource.getParentFile(), prefix.concat(resource.getName()));

                getLog().debug("Renaming resource " + resource + " to " + renamed + "...");

                if (resource.renameTo(renamed)) {
                    getLog().debug("Resource renamed to " + renamed);
                } else {
                    throw new MojoExecutionException("Impossible to rename resource " + resource + " to " + renamed
                            + ", please check the current user has enough rights on the File System");
                }
            }
        }

        getLog().debug(Arrays.toString(includeResources) + " resources in " + destDirectory + " successfully renamed");
    }

    private void downloadSources(final ArtifactProvider artifactProvider, Artifact artifact, File deflatedSourcesDir,
            File checkedOutSourcesDir, String[] exportPackageIncludes) throws MojoExecutionException {
        ArtifactId artifactId = artifact.getId();
        getLog().debug("Downloading sources for " + artifactId.toMvnId() + "...");

        ArtifactId sourcesArtifactId;
        if ( artifact.getMetadata().get(SCM_ID) != null ) {
            sourcesArtifactId = ArtifactId.parse(artifact.getMetadata().get(SCM_ID));
        } else {
            sourcesArtifactId = newArtifacId(artifactId,
                                                    "sources",
                                                    "jar");
        }

        boolean fallback = false;
        try {
            final URL url = retrieve(artifactProvider, sourcesArtifactId);
            if (url != null) {
                File sourcesBundle = IOUtils.getFileFromURL(url, true, null);
                deflate(deflatedSourcesDir, sourcesBundle, exportPackageIncludes);
            } else {
                getLog().warn("Impossible to download sources for " + artifactId.toMvnId() + " due to missing artifact "
                        + sourcesArtifactId.toMvnId() + ", trying source checkout next...");
                fallback = true;
            }
        } catch (Throwable t) {
            getLog().warn("Impossible to download sources for " + artifactId.toMvnId() + " from "
                    + sourcesArtifactId.toMvnId()
                          + " due to "
                          + t.getMessage()
                    + ", trying source checkout next...");
            fallback = true;
        }

        if (fallback) {
            // fallback to Artifacts SCM metadata first
            String connection = artifact.getMetadata().get(SCM_LOCATION);
            String tag = artifact.getMetadata().get(SCM_TAG);

            // Artifacts SCM metadata may not available or are an override, let's fallback to the POM
            ArtifactId pomArtifactId = newArtifacId(artifactId, null, "pom");
            getLog().debug("Falling back to SCM checkout, retrieving POM " + pomArtifactId.toMvnId() + "...");
            // POM file must exist, let the plugin fail otherwise
            final URL pomURL = retrieve(artifactProvider, pomArtifactId);
            if (pomURL == null) {
                throw new MojoExecutionException("Unable to find artifact " + pomArtifactId.toMvnId());
            }

            File pomFile = null;
            try
            {
                pomFile = IOUtils.getFileFromURL(pomURL, true, null);
            } catch (IOException e) {
                throw new MojoExecutionException(e.getMessage());
            }
            getLog().debug("POM " + pomArtifactId.toMvnId() + " successfully retrieved, reading the model...");

            // read model
            Model pomModel = modelBuilder.buildRawModel(pomFile, ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL, false).get();
            getLog().debug("POM model " + pomArtifactId.toMvnId() + " successfully read, processing the SCM...");

            Scm scm = pomModel.getScm();
            if (scm != null) {
                connection = setIfNull(connection, scm.getConnection());
                tag = setIfNull(tag, scm.getTag());
            }

            if (connection == null) {
                getLog().warn("Ignoring sources for artifact " + artifactId.toMvnId() + " : SCM not defined in "
                        + artifactId.toMvnId()
                              + " bundle neither in "
                        + pomArtifactId.toMvnId() + " POM file.");
                return;
            }

            try {
                ScmRepository repository = scmManager.makeScmRepository(connection);

                ScmVersion scmVersion = null;
                if (tag != null) {
                    scmVersion = new ScmTag(tag);
                }

                File basedir = new File(checkedOutSourcesDir, artifactId.getArtifactId());
                if (basedir.exists()) {
                    getLog().debug("Source checkout directory " + basedir + " already exists");
                } else {
                    getLog().debug("Checking out source to directory " + basedir);
                    basedir.mkdirs();
                    ScmFileSet fileSet = new ScmFileSet(basedir);

                    CheckOutScmResult result = null;
                    try {
                        if (scmVersion != null) {
                            result = scmManager.checkOut(repository, fileSet, true);
                        } else {
                            result = scmManager.checkOut(repository, fileSet, scmVersion, true);
                        }
                    } catch (ScmException se) {
                        throw new MojoExecutionException("An error occurred while checking sources from " + repository
                                + " for artifact " + artifactId + " model", se);
                    }

                    if (!result.isSuccess()) {
                        getLog().warn("Ignoring sources for artifact " + artifactId.toMvnId()
                                + " : An error occurred while checking out sources from " + connection + ": "
                                + result.getProviderMessage());
                        return;
                    }
                }

                // retrieve the exact pom location to detect the bundle path in the repo
                DirectoryScanner pomScanner = new DirectoryScanner();
                pomScanner.setBasedir(basedir);
                pomScanner.setIncludes("**/pom.xml");
                pomScanner.scan();
                for (String pomFileLocation : pomScanner.getIncludedFiles()) {
                    pomFile = new File(basedir, pomFileLocation);
                    pomModel = modelBuilder.buildRawModel(pomFile, ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL, false)
                            .get();

                    if (artifactId.getArtifactId().equals(pomModel.getArtifactId())) {
                        basedir = pomFile.getParentFile();
                        break;
                    }
                }

                // copy all interested sources to the proper location
                File javaSources = new File(basedir, "src/main/java");
                if (!javaSources.exists()) { // old modules could still use src/java
                    javaSources = new File(basedir, "src/java");

                    // there could be just resources artifacts
                    if (!javaSources.exists()) {
                        getLog().warn("Ignoring sources for artifact " + artifactId.toMvnId() + " : SCM checkout for "
                                + artifactId.toMvnId()
                                + " does not contain any source.");
                        return;
                    }
                }

                File destDirectory = new File(deflatedSourcesDir, artifactId.toMvnId());
                if (!destDirectory.exists()) {
                    destDirectory.mkdir();
                    DirectoryScanner directoryScanner = new DirectoryScanner();
                    directoryScanner.setBasedir(javaSources);
                    directoryScanner.setIncludes(exportPackageIncludes);
                    directoryScanner.scan();

                    for (String file : directoryScanner.getIncludedFiles()) {
                        File source = new File(javaSources, file);
                        File destination = new File(destDirectory, file);
                        destination.getParentFile().mkdirs();
                        try {
                            FileUtils.copyFile(source, destination);
                        } catch (IOException e) {
                            throw new MojoExecutionException(
                                    "An error occurred while copying sources from " + source + " to " + destination, e);
                        }
                    }
                }

            } catch (ScmRepositoryException se) {
                throw new MojoExecutionException("An error occurred while reading SCM from "
                                                 + connection
                                                 + " connection for bundle "
                                                 + artifactId, se);
            } catch (NoSuchScmProviderException nsspe) {
                getLog().warn("Ignoring sources for artifact " + artifactId.toMvnId()
                        + " : bundle points to an SCM connection "
                               + connection
                               + " which does not specify a valid or supported SCM provider", nsspe);
            }
        }
    }

    private Clause[] getExportedPackages(final Manifest manifest) {
        final String exportPackageHeader = manifest.getMainAttributes().getValue(Constants.EXPORT_PACKAGE);
        final Clause[] exportPackages = Parser.parseHeader(exportPackageHeader);

        return exportPackages;
    }

    private String[] computeExportPackageIncludes(final Clause[] exportedPackages) throws MojoExecutionException {
        final Set<String> exports = new HashSet<>();

        for (Clause exportedPackage : exportedPackages) {
            final String api = exportedPackage.getName();
            exports.add(packageToScannerFiler(api));
        }

        return exports.toArray(new String[exports.size()]);
    }

    /**
     * Compute exports based on api regions
     *
     * @return {@code true} if any region exports a package from this set
     */
    private boolean computeExports(final ApiRegions apiRegions, final Clause[] exportedPackages,
            final ArtifactId bundle)
            throws MojoExecutionException {
        boolean hasExport = false;

        // filter for each region
        for (final Clause exportedPackage : exportedPackages) {
            final String packageName = exportedPackage.getName();

            for (ApiRegion apiRegion : apiRegions.listRegions()) {
                final ApiExport exp = apiRegion.getExportByName(packageName);
                if (exp != null) {
                    if (exp.getProperties().containsKey(PROPERTY_CLAUSE)) {
                        exp.getProperties().put(PROPERTY_CLAUSE, exp.getProperties().get(PROPERTY_CLAUSE).concat(",")
                                .concat(exportedPackage.toString()));
                        exp.getProperties().put(PROPERTY_BUNDLE,
                                exp.getProperties().get(PROPERTY_BUNDLE).concat(",").concat(bundle.toMvnId()));
                    } else {
                        exp.getProperties().put(PROPERTY_CLAUSE, exportedPackage.toString());
                        exp.getProperties().put(PROPERTY_BUNDLE, bundle.toMvnId());
                    }
                    hasExport = true;
                }
            }
        }

        return hasExport;
    }

    private String[] getApiFilters(final ApiRegion region) {
        final List<String> filters = new ArrayList<>();
        for (final ApiExport exp : region.listExports()) {
            final String f = exp.getProperties().get(PROPERTY_FILTER);
            if (f != null) {
                for (final String v : f.split(",")) {
                    filters.add(v);
                }
            }
        }
        return filters.toArray(new String[filters.size()]);
    }

    private String getApiExportClause(final ApiRegion region) {
        final StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (final ApiExport exp : region.listExports()) {
            final String v = exp.getProperties().get(PROPERTY_CLAUSE);
            if (v != null) {
                if (first) {
                    first = false;
                } else {
                    sb.append(',');
                }
                sb.append(v);
            }
        }
        return sb.toString();
    }

    private List<String> recollect(File featureDir, File deflatedDir, ApiRegion apiRegion, File destination) throws MojoExecutionException {
        final List<String> nodeTypes = new LinkedList<>();

        destination.mkdirs();

        DirectoryScanner directoryScanner = new DirectoryScanner();
        directoryScanner.setBasedir(deflatedDir);

        // for each region, include both APIs and resources
        String[] includes;
        if (APIS.equals(destination.getName())) {
            includes = concatenate(getApiFilters(apiRegion), includeResources);
        } else {
            includes = getApiFilters(apiRegion);
        }
        directoryScanner.setIncludes(includes);
        directoryScanner.scan();

        for (String includedFile : directoryScanner.getIncludedFiles()) {
            String fileName = includedFile.substring(includedFile.indexOf(File.separator) + 1);

            File target = new File(destination, fileName);
            target.getParentFile().mkdirs();

            try {
                File source = new File(deflatedDir, includedFile);

                // this to prevent 'unmappable character for encoding UTF8' error
                if (includedFile.endsWith(JAVA_EXTENSION)) {
                    String javaSource = FileUtils.fileRead(source, StandardCharsets.UTF_8.name())
                                                 .replaceAll(NON_ASCII_PATTERN, SPACE);
                    FileUtils.fileWrite(target, StandardCharsets.UTF_8.name(), javaSource);
                } else {
                    if (includedFile.endsWith(CND_EXTENSION)) {
                        nodeTypes.add(fileName);
                    }

                    FileUtils.copyFile(source, target);
                }
            } catch (IOException e) {
                throw new MojoExecutionException("An error occurred while copying file "
                                                 + includedFile
                                                 + " to "
                                                 + destination, e);
            }
        }

        return nodeTypes;
    }

    private File createArchive(ArtifactId featureId, File collectedDir, ApiRegion apiRegion, String classifier,
            List<String> nodeTypes, List<File> resources) throws MojoExecutionException {
        DirectoryScanner directoryScanner = new DirectoryScanner();
        directoryScanner.setBasedir(collectedDir);
        directoryScanner.setIncludes("**/*.*");
        directoryScanner.scan();

        JarArchiver jarArchiver = new JarArchiver();
        for (String includedFile : directoryScanner.getIncludedFiles()) {
            jarArchiver.addFile(new File(collectedDir, includedFile), includedFile);
        }
        if (resources != null) {
            for (final File rsrc : resources) {
                getLog().debug("Adding resource " + rsrc);
                if (rsrc.isDirectory()) {
                    DirectoryScanner ds = new DirectoryScanner();
                    ds.setBasedir(rsrc);
                    ds.setIncludes("**/*.*");
                    ds.scan();

                    for (String includedFile : ds.getIncludedFiles()) {
                        jarArchiver.addFile(new File(rsrc, includedFile), includedFile);
                    }
                } else {
                    jarArchiver.addFile(rsrc, rsrc.getName());
                }
            }
        }
        StringBuilder classifierBuilder = new StringBuilder();
        if (featureId.getClassifier() != null) {
            classifierBuilder.append(mapApClassifier(featureId.getClassifier()))
                             .append('-');
        }
        String finalClassifier = classifierBuilder.append(mapApiRegionName(apiRegion.getName()))
                                                  .append('-')
                                                  .append(classifier)
                                                  .toString();

        String bundleName = String.format("%s-%s", project.getArtifactId(), finalClassifier);
        String symbolicName = bundleName.replace('-', '.');

        MavenArchiveConfiguration archiveConfiguration = new MavenArchiveConfiguration();
        if (APIS.equals(classifier)) {
            // APIs need OSGi Manifest entry
            archiveConfiguration.addManifestEntry("Export-Package", getApiExportClause(apiRegion));
            archiveConfiguration.addManifestEntry("Bundle-Description", project.getDescription());
            archiveConfiguration.addManifestEntry("Bundle-Version", featureId.getOSGiVersion().toString());
            archiveConfiguration.addManifestEntry("Bundle-ManifestVersion", "2");
            archiveConfiguration.addManifestEntry("Bundle-SymbolicName", symbolicName);
            archiveConfiguration.addManifestEntry("Bundle-Name", bundleName);

            if (nodeTypes != null && !nodeTypes.isEmpty()) {
                archiveConfiguration.addManifestEntry("Sling-Nodetypes", StringUtils.join(nodeTypes.iterator(), ","));
            }
        }
        if (project.getOrganization() != null) {
            archiveConfiguration.addManifestEntry("Bundle-Vendor", project.getOrganization().getName());
        }
        archiveConfiguration.addManifestEntry("Specification-Version", featureId.getVersion());
        archiveConfiguration.addManifestEntry("Implementation-Title", bundleName);

        String targetName = String.format("%s-%s-%s.jar", project.getArtifactId(), project.getVersion(), finalClassifier);
        File target = new File(mainOutputDir, targetName);
        MavenArchiver archiver = new MavenArchiver();
        archiver.setArchiver(jarArchiver);
        archiver.setOutputFile(target);

        try {
            archiver.createArchive(mavenSession, project, archiveConfiguration);
            if (this.attachApiJars) {
                projectHelper.attachArtifact(project, JAR_TYPE, finalClassifier, target);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("An error occurred while creating APIs "
                    + target
                    +" archive", e);
        }

        return target;
    }

    private void generateJavadoc(File sourcesDir, File javadocDir, Set<String> javadocClasspath)
            throws MojoExecutionException {
        javadocDir.mkdirs();

        JavadocExecutor javadocExecutor = new JavadocExecutor(javadocDir.getParentFile())
                                          .addArgument("-public")
                                          .addArgument("-d", false)
                                          .addArgument(javadocDir.getAbsolutePath())
                                          .addArgument("-sourcepath", false)
                                          .addArgument(sourcesDir.getAbsolutePath());

        if (isNotEmpty(project.getName())) {
            javadocExecutor.addArgument("-doctitle", false)
                           .addQuotedArgument(project.getName());
        }

        if (isNotEmpty(project.getDescription())) {
            javadocExecutor.addArgument("-windowtitle", false)
                           .addQuotedArgument(project.getDescription());
        }

        if (isNotEmpty(project.getInceptionYear())
                && project.getOrganization() != null
                && isNotEmpty(project.getOrganization().getName())) {
            javadocExecutor.addArgument("-bottom", false)
                           .addQuotedArgument(String.format("Copyright &copy; %s - %s %s. All Rights Reserved",
                                              project.getInceptionYear(),
                                              Calendar.getInstance().get(Calendar.YEAR),
                                              project.getOrganization().getName()));
        }

        if (javadocLinks != null && javadocLinks.length > 0) {
            javadocExecutor.addArguments("-link", javadocLinks);
        }

        if (!javadocClasspath.isEmpty()) {
            javadocExecutor.addArgument("-classpath", false)
                           .addArgument(javadocClasspath, File.pathSeparator);
        }

        // turn off doclint when running Java8
        // http://blog.joda.org/2014/02/turning-off-doclint-in-jdk-8-javadoc.html
        if (SystemUtils.isJavaVersionAtLeast(JavaVersion.JAVA_1_8)) {
            javadocExecutor.addArgument("-Xdoclint:none");
        }

        javadocExecutor.addArgument("--allow-script-in-comments");

        // use the -subpackages to reduce the list of the arguments
        javadocExecutor.addArgument("-subpackages", false);
        javadocExecutor.addArgument(sourcesDir.list(), File.pathSeparator);

        // .addArgument("-J-Xmx2048m")
        javadocExecutor.execute(javadocDir, getLog(), this.ignoreJavadocErrors);
    }

    private static ArtifactId newArtifacId(ArtifactId original, String classifier, String type) {
        return new ArtifactId(original.getGroupId(),
                              original.getArtifactId(),
                              original.getVersion(),
                              classifier,
                              type);
    }



    private static String packageToScannerFiler(String api) {
        return "**/" + api.replace('.', '/') + "/*";
    }

    private static String[] concatenate(String[] a, String[] b) {
        String[] result = new String[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static File newDir(File parent, String child) {
        File dir = new File(parent, child);
        dir.mkdirs();
        return dir;
    }

    private static <T> T setIfNull(T what, T with) {
        if (what == null) {
            return with;
        }
        return what;
    }

    private static boolean isNotEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    // artifact filter

    @Override
    public boolean include(org.apache.maven.artifact.Artifact artifact) {
        if (org.apache.maven.artifact.Artifact.SCOPE_TEST.equals(artifact.getScope())) {
            return false;
        }
        return true;
    }

    private List<String> getPackages(final File file, final String extension) throws MojoExecutionException {
        final String postfix = ".".concat(extension);
        final Set<String> packages = new HashSet<>();
        try (final JarInputStream jis = new JarInputStream(new FileInputStream(file))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                if (entry.getName().endsWith(postfix)) {
                    final int lastPos = entry.getName().lastIndexOf('/');
                    if (lastPos != -1) {
                        packages.add(entry.getName().substring(0, lastPos).replace('/', '.'));
                    }
                }
                jis.closeEntry();
            }
        } catch (final IOException ioe) {
            throw new MojoExecutionException("Unable to scan file " + file + " : " + ioe.getMessage());
        }
        final List<String> sorted = new ArrayList<>(packages);
        Collections.sort(sorted);
        return sorted;
    }
}

