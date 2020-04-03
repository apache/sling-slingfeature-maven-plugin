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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.jar.JarEntry;
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
import org.apache.sling.feature.maven.mojos.ApisJarContext.ArtifactInfo;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.archiver.util.DefaultFileSet;
import org.codehaus.plexus.components.io.fileselectors.FileSelector;
import org.codehaus.plexus.components.io.fileselectors.IncludeExcludeFileSelector;
import org.osgi.framework.Constants;

/**
 * Generates the APIs JARs for the selected feature files.
 */
@Mojo(name = "apis-jar",
    defaultPhase = LifecyclePhase.PACKAGE,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true
)
public class ApisJarMojo extends AbstractIncludingFeatureMojo {

    /** Alternative ID to a source artifact. */
    private static final String SCM_ID = "source-ids";

    private static final String SCM_TAG = "scm-tag";

    private static final String SCM_LOCATION = "scm-location";

    private static final String SCM_ENCODING = "scm-encoding";

    private static final String APIS = "apis";

    private static final String SOURCES = "sources";

    private static final String JAVADOC = "javadoc";

    private static final String JAR_TYPE = "jar";

    private static final String JAVA_EXTENSION = ".java";

    private static final String CLASS_EXTENSION = ".class";

    private static final String CND_EXTENSION = ".cnd";

    /**
     * Select the features for api generation.
     * Separate api jars will be generated for each feature.
     */
    @Parameter
    private FeatureSelectionConfig selection;

    /**
     * Patterns identifying which resources to include from bundles.
     * This can be used to include files like license or notices files.
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

    /**
     * List of javadoc links used in the javadoc generation.
     */
    @Parameter
    private String[] javadocLinks;

    /**
     * Ignore errors in javadoc generation
     */
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
     * If enabled, the created api jars will be attached to the project
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

    /**
     * Generate api jar
     */
    @Parameter(defaultValue = "true")
    private boolean generateApiJar;

    /**
     * Generate the sources jar
     */
    @Parameter(defaultValue = "true")
    private boolean generateSourceJar;

    /**
     * Generate the javadoc jar
     */
    @Parameter(defaultValue = "true")
    private boolean generateJavadocJar;

    /**
     * Optional version to be put into the manifest of the created jars
     */
    @Parameter
    private String apiVersion;

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

    @Parameter
    private String javadocSourceLevel;

    private final Pattern pomPropertiesPattern = Pattern.compile("META-INF/maven/[^/]+/[^/]+/pom.properties");

    /** Artifact Provider. */
    private final ArtifactProvider artifactProvider = new BaseArtifactProvider();

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

            for (final Feature feature : features) {
                onFeature(feature);
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
    private String mapApiClassifier(final String classifier) {
        if (this.apiClassifierMappings != null && this.apiClassifierMappings.containsKey(classifier)) {
            return this.apiClassifierMappings.get(classifier);
        }
        return classifier;
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
        ApiRegions regions = new ApiRegions();

        Extensions extensions = feature.getExtensions();
        Extension apiRegionsExtension = extensions.getByName(ApiRegions.EXTENSION_NAME);
        if (apiRegionsExtension != null) {
            if (apiRegionsExtension.getJSONStructure() == null) {
                getLog().info(
                        "Feature file " + feature.getId().toMvnId() + " declares an empty '" + ApiRegions.EXTENSION_NAME
                    + "' extension, no API JAR will be created");
                regions = null;
            } else {
                ApiRegions sourceRegions;
                try {
                    sourceRegions = ApiRegions
                            .parse((JsonArray) apiRegionsExtension.getJSONStructure());
                } catch (final IOException ioe) {
                    throw new MojoExecutionException(ioe.getMessage(), ioe);
                }

                // calculate all api-regions first, taking the inheritance in account
                for (final ApiRegion r : sourceRegions.listRegions()) {
                    if (r.getParent() != null && !this.incrementalApis) {
                        for (final ApiExport exp : r.getParent().listExports()) {
                            r.add(exp);
                        }
                    }
                    if (isRegionIncluded(r.getName())) {
                        getLog().debug("API Region " + r.getName()
                                    + " will not processed due to the configured include/exclude list");
                        regions.add(r);
                    }
                }

                if (regions.isEmpty()) {
                    getLog().info("Feature file " + feature.getId().toMvnId()
                            + " has no included api regions, no API JAR will be created");
                    regions = null;
                }
            }
        } else {
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
    private void onFeature(final Feature feature)
            throws MojoExecutionException {
        getLog().info(MessageUtils.buffer().a("Creating API JARs for Feature ").strong(feature.getId().toMvnId())
                .a(" ...").toString());

        final ApiRegions regions = getApiRegions(feature);
        if (regions == null) {
            // wrongly configured api regions - skip execution, info is logged already so we can just return
            return;
        }

        // create an output directory per feature
        final File featureDir = new File(mainOutputDir, feature.getId().getArtifactId());
        final ApisJarContext ctx = new ApisJarContext(this.mainOutputDir, feature.getId(), regions);

        // for each bundle included in the feature file and record directories
        for (final Artifact artifact : feature.getBundles()) {
            onArtifact(ctx, artifact);
        }

        ctx.getPackagesWithoutJavaClasses().forEach( p -> getLog().info("Exported package " + p + " does not contain any java classes"));

        // recollect and package stuff
        for (final ApiRegion apiRegion : regions.listRegions()) {
            final File regionDir = new File(featureDir, apiRegion.getName());

            if (generateApiJar) {
                final File apiJar = createArchive(ctx, apiRegion, APIS, this.apiResources);
                report(apiJar, APIS, apiRegion, "class", ctx);
            }

            if (generateSourceJar) {
                final File sourceJar = createArchive(ctx, apiRegion, SOURCES, this.apiSourceResources);
                report(sourceJar, SOURCES, apiRegion, "java", ctx);
            }

            if (generateJavadocJar) {
                final File javadocsDir = new File(regionDir, JAVADOC);
                if ( generateJavadoc(ctx, apiRegion, javadocsDir) ) {
                    ctx.setJavadocDir(javadocsDir);
                    final File javadocJar = createArchive(ctx, apiRegion, JAVADOC, this.apiJavadocResources);
                    report(javadocJar, JAVADOC, apiRegion, "html", ctx);
                } else {
                    getLog().warn("Javadoc JAR will NOT be generated - sources directory " + ctx.getDeflatedSourcesDir()
                            + " was empty or contained no Java files!");
                }
            }
        }

        getLog().info(MessageUtils.buffer().a("APIs JARs for Feature ").debug(feature.getId().toMvnId())
                .a(" succesfully created").toString());
    }

    private void report(final File jarFile, final String apiType, final ApiRegion apiRegion, final String extension, ApisJarContext ctx) throws MojoExecutionException {
        final List<String> packages = getPackages(jarFile, extension);
        final List<ApiExport> missing = new ArrayList<>();
        for (final ApiExport exp : apiRegion.listExports()) {
            String packageName = exp.getName();
            if (!packages.remove(packageName) && !ctx.getPackagesWithoutJavaClasses().contains(packageName)) {
                missing.add(exp);
            }
        }
        if (missing.isEmpty() && packages.isEmpty()) {
            getLog().info("Verified " + apiType + " jar for region " + apiRegion.getName());
        } else {
            Collections.sort(missing);
            getLog().info(apiType + " jar for region " + apiRegion.getName() + " has " + ( missing.size() + packages.size() ) + " errors:");
            for (final ApiExport m : missing) {
                final List<String> candidates = new ArrayList<>();
                for(final ArtifactInfo info : ctx.getArtifactInfos()) {
                    for(final Clause clause : info.getUsedExportedPackages(apiRegion)) {
                        if ( m.getName().equals(clause.getName())) {
                            candidates.add(info.getId().toMvnName());
                            break;
                        }
                    }
                }
                getLog().info("- Missing package " + m.getName() + " from bundle(s) "
                        + String.join(",", candidates));
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
        try (JarInputStream jis = new JarInputStream(new FileInputStream(bundleFile))) {
            getLog().debug("Reading Manifest headers from bundle " + bundleFile);

            final Manifest manifest = jis.getManifest();

            if (manifest == null) {
                throw new MojoExecutionException("Artifact + " + artifactId.toMvnId() + " does not  have a manifest.");
            }
            return manifest;
        } catch (final IOException e) {
            throw new MojoExecutionException("An error occurred while reading manifest from file " + bundleFile
                    + " for artifact " + artifactId.toMvnId(), e);
        }
    }

    private void onArtifact(final ApisJarContext ctx, final Artifact artifact)
    throws MojoExecutionException {
        final File bundleFile = getArtifactFile(artifactProvider, artifact.getId());

        final Manifest manifest = getManifest(artifact.getId(), bundleFile);

        // check if the bundle is exporting packages?
        final Clause[] exportedPackageClauses = this.getExportedPackages(manifest);
        if (exportedPackageClauses.length > 0) {

            // calculate the exported versioned packages in the manifest file for each
            // region
            // and calculate the exported versioned packages in the manifest file for each
            // region
            final Set<String> usedExportedPackages = computeUsedExportPackages(ctx.getApiRegions(), exportedPackageClauses, artifact.getId());

            if ( !usedExportedPackages.isEmpty()) {
                final ArtifactInfo info = ctx.addArtifactInfo(artifact.getId());
                info.setUsedExportedPackages(usedExportedPackages);

                // calculate per region packages
                for(final ApiRegion region : ctx.getApiRegions().listRegions()) {
                    final Set<Clause> usedExportedPackagesPerRegion = computeUsedExportPackages(region, exportedPackageClauses, artifact.getId());
                    info.setUsedExportedPackages(region, usedExportedPackagesPerRegion);
                }

                info.setBinDirectory(new File(ctx.getDeflatedBinDir(), info.getId().toMvnName()));
                info.setSourceDirectory(new File(ctx.getDeflatedSourcesDir(), info.getId().toMvnName()));

                final boolean skipBinDeflate = info.getBinDirectory().exists();
                if ( skipBinDeflate ) {
                    getLog().debug("Artifact " + info.getId().toMvnName() + " already deflated");
                }
                final boolean skipSourceDeflate = info.getSourceDirectory().exists();
                if ( skipSourceDeflate ) {
                    getLog().debug("Source for artifact " + info.getId().toMvnName() + " already deflated");
                }

                processBinary(ctx, info, bundleFile, artifact, skipBinDeflate, skipSourceDeflate);

                // check if the bundle wraps other bundles
                computeWrappedBundles(ctx, info, manifest, skipBinDeflate, skipSourceDeflate);

                postProcessArtifact(ctx, info, artifact);

                if ( !info.getSourceDirectory().exists() ) {
                    info.setSourceDirectory(null);
                }

                if ( generateJavadocJar ) {
                    buildJavadocClasspath(artifact.getId()).forEach( ctx::addJavadocClasspath );
                }
            }

        }
    }

    /**
     * Post process
     * <ul>
     * <li>Find node types
     * <li>Find empty packages
     * <li>Find empty directories and remove them
     * <li>Clean up sources - if encoding is not UTF-8
     * </ul>
     * @param ctx The context
     * @param info The artifact info
     * @throws MojoExecutionException
     */
    private void postProcessArtifact(final ApisJarContext ctx, final ArtifactInfo info, final Artifact artifact)
    throws MojoExecutionException {
        // binary post processing
        this.postProcessBinDirectory(ctx, info, info.getBinDirectory(), "");

        // source post processing
        if ( generateSourceJar || generateJavadocJar && info.getSourceDirectory() != null ) {
            final String encoding = artifact.getMetadata().getOrDefault(SCM_ENCODING, "UTF-8");
            if ( !"UTF-8".equals(encoding)) {
                this.cleanupSources(info.getSourceDirectory(), encoding);
            }
        }

    }

    private void postProcessBinDirectory(final ApisJarContext ctx, final ArtifactInfo info, final File dir, final String pck) {
        boolean hasJavaFile = false;
        for(final File child : dir.listFiles()) {
            if ( child.isFile() ) {
                if ( child.getName().endsWith(CND_EXTENSION) ) {
                    ctx.addNodeType(child.getName());
                } else if ( child.getName().endsWith(CLASS_EXTENSION)) {
                    hasJavaFile = true;
                }
            } else {
                postProcessBinDirectory(ctx, info, child, pck.isEmpty() ? child.getName() : pck.concat(".").concat(child.getName()));
            }
        }
        if ( dir.listFiles().length == 0 && !pck.isEmpty() ) {
            // empty dir -> remove
            dir.delete();
        } else if ( !hasJavaFile && info.getUsedExportedPackages().contains(pck) ) {

            // We need to record this kind of packages and ensure we don't trigger warnings for them
            // when checking the api jars for correctness.</p>
            getLog().debug("No classes found in " + pck);
            ctx.addPackageWithoutJavaClasses(pck);
        }
    }

    private void processBinary(final ApisJarContext ctx,
            final ArtifactInfo info,
            final File binFile,
            final Artifact binArtifact,
            final boolean skipBinDeflate,
            final boolean skipSourceDeflate)
    throws MojoExecutionException {
        if ( !skipBinDeflate ) {
            // deflate all bundles first, in order to copy APIs and resources later,
            // depending to the region
            final String[] exportedPackagesAndWrappedBundles = Stream
                    .concat(Stream.concat(Stream.of(info.getUsedExportedPackageIncludes()),
                            Stream.of("**/*.jar")),
                            Stream.of(includeResources))
                    .toArray(String[]::new);

            deflate(info.getBinDirectory(), binFile, exportedPackagesAndWrappedBundles);

            // renaming potential name-collapsing resources
            renameResources(info, binArtifact.getId());
        }

        // download sources
        if ( generateSourceJar || generateJavadocJar ) {
            if ( !skipSourceDeflate ) {
                downloadSources(ctx, info, binArtifact);
            }
        }

    }

    private void cleanupSources(final File dir, final String readEncoding) throws MojoExecutionException {
        for(final File child : dir.listFiles()) {
            if ( child.isDirectory() ) {
                cleanupSources(child, readEncoding);
            } else if ( child.getName().endsWith(JAVA_EXTENSION)) {
                try {
                    final String javaSource = FileUtils.fileRead(child, readEncoding);
                    FileUtils.fileWrite(child, StandardCharsets.UTF_8.name(), javaSource);
                } catch ( final IOException ioe) {
                    throw new MojoExecutionException("Unable to clean up java source " + child, ioe);
                }
            }
        }
    }

    private void computeWrappedBundles(final ApisJarContext ctx,
            final ArtifactInfo info,
            final Manifest manifest,
            final boolean skipBinDeflate,
            final boolean skipSourceDeflate)
    throws MojoExecutionException {

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

            final File wrappedJar = new File(info.getBinDirectory(), jarName);
            getLog().debug("Processing wrapped bundle " + wrappedJar);

            final Properties properties = new Properties();

            try (final JarInputStream jis = new JarInputStream(new FileInputStream(wrappedJar))) {
                JarEntry jarEntry = null;
                while ( (jarEntry = jis.getNextJarEntry()) != null ) {
                    if (!jarEntry.isDirectory()
                            && pomPropertiesPattern.matcher(jarEntry.getName()).matches()) {
                        getLog().debug("Loading Maven GAV from " + wrappedJar + '!' + jarEntry.getName());
                        properties.load(jis);
                        break;
                    }
                    jis.closeEntry();
                }
            } catch (IOException e) {
                throw new MojoExecutionException("An error occurred while processing wrapped bundle " + wrappedJar, e);
            }

            if (properties.isEmpty()) {
                getLog().warn("No Maven GAV info attached to wrapped bundle " + wrappedJar + ", it will be ignored");
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
                final File bundleFile = getArtifactFile(artifactProvider, syntheticArtifact.getId());

                processBinary(ctx, info, bundleFile, syntheticArtifact, skipBinDeflate, skipSourceDeflate);
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

    private void deflate(final File destDirectory, final File artifact, final String...includes) throws MojoExecutionException {
        getLog().debug("Deflating artifact " + artifact.getName() + "...");
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
            unArchiver.setOverwrite(false);
            unArchiver.extract();
        } catch (NoSuchArchiverException e) {
            throw new MojoExecutionException(
                    "An error occurred while deflating file " + artifact + " to directory " + destDirectory, e);
        }

        getLog().debug("Artifact " + artifact + " successfully deflated");
    }

    private void renameResources(final ArtifactInfo info, final ArtifactId artifactId) throws MojoExecutionException {
        if (includeResources == null || includeResources.length == 0) {
            getLog().debug("No configured resources to rename in " + info.getBinDirectory());
        }

        getLog().debug("Renaming " + Arrays.toString(includeResources) + " files in " + info.getBinDirectory() + "...");

        DirectoryScanner directoryScanner = new DirectoryScanner();
        directoryScanner.setBasedir(info.getBinDirectory());
        directoryScanner.setIncludes(includeResources);
        directoryScanner.scan();

        if (directoryScanner.getIncludedFiles().length == 0) {
            getLog().debug("No " + Arrays.toString(includeResources) + " resources in " + info.getBinDirectory() + " to be renamed found.");
            return;
        }

        for (final String resourceName : directoryScanner.getIncludedFiles()) {
            final File resource = new File(info.getBinDirectory(), resourceName);
            if ( !info.getIncludedResources().contains(resource) ) {
                final String prefix = artifactId.toMvnName().concat("-");

                if (resource.getName().startsWith(prefix)) {
                    getLog().debug("No need to rename " + resource);
                    info.getIncludedResources().add(resource);
                } else {
                    File renamed = new File(resource.getParentFile(), prefix.concat(resource.getName()));

                    getLog().debug("Renaming resource " + resource + " to " + renamed + "...");

                    if (resource.renameTo(renamed)) {
                        getLog().debug("Resource renamed to " + renamed);
                        info.getIncludedResources().add(renamed);
                    } else {
                        throw new MojoExecutionException("Impossible to rename resource " + resource + " to " + renamed
                                + ", please check the current user has enough rights on the File System");
                    }
                }
            }
        }

        getLog().debug(Arrays.toString(includeResources) + " resources in " + info.getBinDirectory() + " successfully renamed");
    }

    private boolean downloadSourceAndDeflate(final ApisJarContext ctx,
            final ArtifactInfo info,
            final ArtifactId sourcesArtifactId,
            final boolean allowFallback) throws MojoExecutionException {
        boolean failed = false;
        try {
            final URL url = retrieve(artifactProvider, sourcesArtifactId);
            if (url != null) {
                File sourcesBundle = IOUtils.getFileFromURL(url, true, null);
                deflate(info.getSourceDirectory(), sourcesBundle, info.getUsedExportedPackageIncludes());
             } else {
                if (!allowFallback) {
                    throw new MojoExecutionException("Unable to download sources for " + info.getId().toMvnId()
                            + " due to missing artifact " + sourcesArtifactId.toMvnId());
                }
                getLog().warn("Unable to download sources for " + info.getId().toMvnId() + " due to missing artifact "
                        + sourcesArtifactId.toMvnId() + ", trying source checkout next...");
                failed = true;
            }
        } catch (final MojoExecutionException mee) {
            throw mee;
        } catch (final Throwable t) {
            if (!allowFallback) {
                throw new MojoExecutionException("Unable to download sources for " + info.getId().toMvnId()
                        + " due to missing artifact " + sourcesArtifactId.toMvnId());
            }
            getLog().warn("Unable to download sources for " + info.getId().toMvnId() + " from "
                    + sourcesArtifactId.toMvnId() + " due to " + t.getMessage() + ", trying source checkout next...");
            failed = true;
        }
        return failed;
    }

    private void downloadSources(final ApisJarContext ctx, final ArtifactInfo info, final Artifact artifact) throws MojoExecutionException {
        ArtifactId artifactId = artifact.getId();
        getLog().debug("Downloading sources for " + artifactId.toMvnId() + "...");

        String scmId = artifact.getMetadata().get(SCM_ID);
        String scmLocation = artifact.getMetadata().get(SCM_LOCATION);
        if ( scmId != null && scmLocation != null) {
            throw new MojoExecutionException("Both " + SCM_ID + " and " + SCM_LOCATION + " are defined for " + artifactId);
        }

        boolean fallbackToScmCheckout = false;

        if ( scmId != null ) {
            final String value = scmId;
            for (final String id : value.split(",")) {
                final ArtifactId sourcesArtifactId = ArtifactId.parse(id);
                downloadSourceAndDeflate(ctx, info, sourcesArtifactId, false);
            }
        } else if ( scmLocation != null ) {
            checkoutSourcesFromSCM(ctx, info, artifact);
        } else {
            final ArtifactId sourcesArtifactId = artifactId.changeClassifier("sources").changeType("jar");
            fallbackToScmCheckout = downloadSourceAndDeflate(ctx, info, sourcesArtifactId, true);
        }

        if ( fallbackToScmCheckout ) {
            checkoutSourcesFromSCM(ctx, info, artifact);
        }
    }

    private void checkoutSourcesFromSCM(final ApisJarContext ctx,
            final ArtifactInfo info,
            final Artifact sourceArtifact)
    throws MojoExecutionException {
        // fallback to Artifacts SCM metadata first
        String connection = sourceArtifact.getMetadata().get(SCM_LOCATION);
        String tag = sourceArtifact.getMetadata().get(SCM_TAG);

        // Artifacts SCM metadata may not available or are an override, let's fallback to the POM
        final ArtifactId pomArtifactId = sourceArtifact.getId().changeClassifier(null).changeType("pom");
        getLog().debug("Falling back to SCM checkout, retrieving POM " + pomArtifactId.toMvnId() + "...");
        // POM file must exist, let the plugin fail otherwise
        final URL pomURL = retrieve(artifactProvider, pomArtifactId);
        if (pomURL == null) {
            throw new MojoExecutionException("Unable to find artifact " + pomArtifactId.toMvnId());
        }

        File pomFile = null;
        try {
            pomFile = IOUtils.getFileFromURL(pomURL, true, null);
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage());
        }
        getLog().debug("POM " + pomArtifactId.toMvnId() + " successfully retrieved, reading the model...");

        // read model
        Model pomModel = modelBuilder.buildRawModel(pomFile, ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL, false).get();
        getLog().debug("POM model " + pomArtifactId.toMvnId() + " successfully read, processing the SCM...");

        final Scm scm = pomModel.getScm();
        if (scm != null) {
            connection = setIfNull(connection, scm.getConnection());
            tag = setIfNull(tag, scm.getTag());
        }

        if (connection == null) {
            getLog().warn("Ignoring sources for artifact " + sourceArtifact.getId().toMvnId() + " : SCM not defined in "
                    + sourceArtifact.getId().toMvnId()
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

            File basedir = new File(ctx.getCheckedOutSourcesDir(), sourceArtifact.getId().toMvnName());
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
                            + " for artifact " + sourceArtifact.getId().toMvnId() + " model", se);
                }

                if (!result.isSuccess()) {
                    getLog().warn("Ignoring sources for artifact " + sourceArtifact.getId().toMvnId()
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

                if (sourceArtifact.getId().getArtifactId().equals(pomModel.getArtifactId())) {
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
                    getLog().warn("Ignoring sources for artifact " + sourceArtifact.getId().toMvnId() + " : SCM checkout for "
                            + sourceArtifact.getId().toMvnId()
                            + " does not contain any source.");
                    return;
                }
            }

            final File sourceDirectory = new File(ctx.getDeflatedSourcesDir(), info.getId().toMvnName());
            info.setSourceDirectory(sourceDirectory);
            sourceDirectory.mkdir();

            final DirectoryScanner directoryScanner = new DirectoryScanner();
            directoryScanner.setBasedir(javaSources);
            directoryScanner.setIncludes(info.getUsedExportedPackageIncludes());
            directoryScanner.scan();

            for (String file : directoryScanner.getIncludedFiles()) {
                final File source = new File(javaSources, file);
                final File destination = new File(sourceDirectory, file);
                destination.getParentFile().mkdirs();
                try {
                    FileUtils.copyFile(source, destination);
                } catch (IOException e) {
                    throw new MojoExecutionException(
                                "An error occurred while copying sources from " + source + " to " + destination, e);
                }
            }

        } catch (ScmRepositoryException se) {
            throw new MojoExecutionException("An error occurred while reading SCM from "
                                             + connection
                                             + " connection for bundle "
                                             + sourceArtifact.getId(), se);
        } catch (NoSuchScmProviderException nsspe) {
            getLog().warn("Ignoring sources for artifact " + sourceArtifact.getId().toMvnId()
                    + " : bundle points to an SCM connection "
                           + connection
                           + " which does not specify a valid or supported SCM provider", nsspe);
        }
    }

    private Clause[] getExportedPackages(final Manifest manifest) {
        final String exportPackageHeader = manifest.getMainAttributes().getValue(Constants.EXPORT_PACKAGE);
        final Clause[] exportPackages = Parser.parseHeader(exportPackageHeader);

        return exportPackages;
    }

    /**
     * Compute exports based on all api regions
     *
     * @return List of packages exported by this bundle and used in the region
     */
    private Set<Clause> computeUsedExportPackages(final ApiRegion apiRegion,
            final Clause[] exportedPackages,
            final ArtifactId bundle)
            throws MojoExecutionException {
        final Set<Clause> result = new HashSet<>();

        // filter for each region
        for (final Clause exportedPackage : exportedPackages) {
            final String packageName = exportedPackage.getName();

            final ApiExport exp = apiRegion.getExportByName(packageName);
            if (exp != null) {
                result.add(exportedPackage);
            }
        }

        return result;
    }

    /**
     * Compute exports based on a single api region
     *
     * @return List of packages exported by this bundle and used in the region
     */
    private Set<String> computeUsedExportPackages(final ApiRegions apiRegions,
            final Clause[] exportedPackages,
            final ArtifactId bundle)
            throws MojoExecutionException {
        final Set<String> result = new HashSet<>();

        // filter for each region
        for (final Clause exportedPackage : exportedPackages) {
            final String packageName = exportedPackage.getName();

            for (ApiRegion apiRegion : apiRegions.listRegions()) {
                final ApiExport exp = apiRegion.getExportByName(packageName);
                if (exp != null) {
                    result.add(exportedPackage.getName());
                }
            }
        }

        return result;
    }

    private String getApiExportClause(final ApisJarContext ctx, final ApiRegion region) {
        final StringBuilder sb = new StringBuilder();
        boolean first = true;
        for(final ArtifactInfo info : ctx.getArtifactInfos()) {
            for(final Clause clause : info.getUsedExportedPackages(region)) {
                if (first) {
                    first = false;
                } else {
                    sb.append(',');
                }
                sb.append(clause.toString());
            }
        }
        return sb.toString();
    }

    private File createArchive(final ApisJarContext ctx,
            final ApiRegion apiRegion,
            final String classifier,
            final List<File> resources) throws MojoExecutionException {
        final JarArchiver jarArchiver = new JarArchiver();

        if ( APIS.equals(classifier) || SOURCES.equals(classifier) ) {
            // api or source
            for(final ArtifactInfo includeEntry : ctx.getArtifactInfos()) {
                final File dir = APIS.equals(classifier) ? includeEntry.getBinDirectory() : includeEntry.getSourceDirectory();

                final String[] usedExportedPackageIncludes = includeEntry.getUsedExportedPackageIncludes(apiRegion);
                if ( usedExportedPackageIncludes.length > 0 ) {
                    getLog().debug("Adding directory " + dir.getName() + " with " + Arrays.toString(usedExportedPackageIncludes));
                    final DefaultFileSet fileSet = new DefaultFileSet(dir);
                    fileSet.setIncludingEmptyDirectories(false);
                    fileSet.setIncludes(usedExportedPackageIncludes);
                    jarArchiver.addFileSet(fileSet);
                }
            }
        } else {
            // javadoc
            final DefaultFileSet fileSet = new DefaultFileSet(ctx.getJavadocDir());
            jarArchiver.addFileSet(fileSet);
        }

        // add included resources
        for(final ArtifactInfo includeEntry : ctx.getArtifactInfos()) {
            final int prefixLength = includeEntry.getBinDirectory().getAbsolutePath().length() + 1;
            for(final File resource : includeEntry.getIncludedResources()) {
                final String name = resource.getAbsolutePath().substring(prefixLength);
                jarArchiver.addFile(resource, name);
                getLog().debug("Adding resource " + name);
            }
        }

        // add additional resources
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

        // build classifier
        final StringBuilder classifierBuilder = new StringBuilder();
        if (ctx.getFeatureId().getClassifier() != null) {
            classifierBuilder.append(mapApiClassifier(ctx.getFeatureId().getClassifier()))
                             .append('-');
        }
        final String finalClassifier = classifierBuilder.append(mapApiRegionName(apiRegion.getName()))
                                                  .append('-')
                                                  .append(classifier)
                                                  .toString();

        final String artifactName = String.format("%s-%s", project.getArtifactId(), finalClassifier);

        final ArtifactId apiId = apiVersion == null ? ctx.getFeatureId() : ctx.getFeatureId().changeVersion(this.apiVersion);

        MavenArchiveConfiguration archiveConfiguration = new MavenArchiveConfiguration();
        archiveConfiguration.setAddMavenDescriptor(false);
        if (APIS.equals(classifier)) {
            // APIs need OSGi Manifest entry
            String symbolicName = artifactName.replace('-', '.');
            archiveConfiguration.addManifestEntry("Export-Package", getApiExportClause(ctx, apiRegion));
            archiveConfiguration.addManifestEntry("Bundle-Description", project.getDescription());
            archiveConfiguration.addManifestEntry("Bundle-Version", apiId.getOSGiVersion().toString());
            archiveConfiguration.addManifestEntry("Bundle-ManifestVersion", "2");
            archiveConfiguration.addManifestEntry("Bundle-SymbolicName", symbolicName);
            archiveConfiguration.addManifestEntry("Bundle-Name", artifactName);

            if (!ctx.getNodeTypes().isEmpty()) {
                archiveConfiguration.addManifestEntry("Sling-Nodetypes", String.join(",", ctx.getNodeTypes()));
            }
            if (project.getOrganization() != null) {
                archiveConfiguration.addManifestEntry("Bundle-Vendor", project.getOrganization().getName());
            }

            // add provide / require capability to make the jar unresolvable
            archiveConfiguration.addManifestEntry("Provide-Capability", "osgi.unresolvable");
            archiveConfiguration.addManifestEntry("Require-Capability", "osgi.unresolvable;filter:=\"(&(must.not.resolve=*)(!(must.not.resolve=*)))\",osgi.ee;filter:=\"(&(osgi.ee=JavaSE/compact2)(version=1.8))\"");
        }
        archiveConfiguration.addManifestEntry("Implementation-Version", apiId.getVersion());
        archiveConfiguration.addManifestEntry("Specification-Version", apiId.getVersion());

        archiveConfiguration.addManifestEntry("Implementation-Title", artifactName);
        archiveConfiguration.addManifestEntry("Specification-Title", artifactName);
        if (project.getOrganization() != null) {
            archiveConfiguration.addManifestEntry("Implementation-Vendor", project.getOrganization().getName());
            archiveConfiguration.addManifestEntry("Specification-Vendor", project.getOrganization().getName());
        }

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

    private boolean generateJavadoc(final ApisJarContext ctx, final ApiRegion region, final File javadocDir)
            throws MojoExecutionException {
        final List<String> sourceDirectories = new ArrayList<>();
        final Set<String> javadocPackages = new HashSet<>();
        for(final ArtifactInfo info : ctx.getArtifactInfos()) {
            boolean addDirectory = false;
            for(final Clause clause : info.getUsedExportedPackages(region)) {
                addDirectory = true;
                javadocPackages.add(clause.getName());
            }
            if ( addDirectory && info.getSourceDirectory() != null ) {
                sourceDirectories.add(info.getSourceDirectory().getAbsolutePath());
            }
        }

        if (javadocPackages.isEmpty()) {
            return false;
        }

        javadocDir.mkdirs();

        JavadocExecutor javadocExecutor = new JavadocExecutor(javadocDir.getParentFile())
                                          .addArgument("-public")
                                          .addArgument("-encoding", false)
                                          .addArgument("UTF-8")
                                          .addArgument("-charset", false)
                                          .addArgument("UTF-8")
                                          .addArgument("-docencoding", false)
                                          .addArgument("UTF-8")
                                          .addArgument("-d", false)
                                          .addArgument(javadocDir.getAbsolutePath())
                                          .addArgument("-sourcepath", false)
                                          .addArgument(String.join(":", sourceDirectories));

        if (isNotEmpty(javadocSourceLevel)) {
            javadocExecutor.addArgument("-source", false)
                           .addArgument(javadocSourceLevel);
        }

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

        if (!ctx.getJavadocClasspath().isEmpty()) {
            javadocExecutor.addArgument("-classpath", false)
                           .addArgument(ctx.getJavadocClasspath(), File.pathSeparator);
        }

        // turn off doclint when running Java8
        // http://blog.joda.org/2014/02/turning-off-doclint-in-jdk-8-javadoc.html
        if (SystemUtils.isJavaVersionAtLeast(JavaVersion.JAVA_1_8)) {
            javadocExecutor.addArgument("-Xdoclint:none");
        }

        javadocExecutor.addArgument("--allow-script-in-comments");

        // list packages
        javadocExecutor.addArguments(javadocPackages);

        // .addArgument("-J-Xmx2048m")
        javadocExecutor.execute(javadocDir, getLog(), this.ignoreJavadocErrors);

        return true;
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

