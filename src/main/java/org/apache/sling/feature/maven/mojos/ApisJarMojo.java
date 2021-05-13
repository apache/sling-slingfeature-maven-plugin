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
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.felix.utils.manifest.Clause;
import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.model.Scm;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
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
import org.apache.sling.feature.ExecutionEnvironmentExtension;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.builder.ArtifactProvider;
import org.apache.sling.feature.extension.apiregions.api.ApiExport;
import org.apache.sling.feature.extension.apiregions.api.ApiRegion;
import org.apache.sling.feature.extension.apiregions.api.ApiRegions;
import org.apache.sling.feature.io.IOUtils;
import org.apache.sling.feature.maven.mojos.apis.ApisJarContext;
import org.apache.sling.feature.maven.mojos.apis.ApisJarContext.ArtifactInfo;
import org.apache.sling.feature.maven.mojos.apis.ApisUtil;
import org.apache.sling.feature.maven.mojos.apis.ArtifactType;
import org.apache.sling.feature.maven.mojos.apis.DirectorySource;
import org.apache.sling.feature.maven.mojos.apis.FileSource;
import org.apache.sling.feature.maven.mojos.apis.JavadocExecutor;
import org.apache.sling.feature.maven.mojos.apis.JavadocLinks;
import org.apache.sling.feature.maven.mojos.apis.RegionSupport;
import org.apache.sling.feature.maven.mojos.apis.spi.Processor;
import org.apache.sling.feature.maven.mojos.apis.spi.ProcessorContext;
import org.apache.sling.feature.maven.mojos.apis.spi.Source;
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
@Mojo(name = "apis-jar", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class ApisJarMojo extends AbstractIncludingFeatureMojo {

    /**
     * Select the features for api generation. Separate api jars will be generated
     * for each feature.
     */
    @Parameter
    private FeatureSelectionConfig selection;

    /**
     * Patterns identifying which resources to include from bundles. This can be
     * used to include files like license or notices files. Starting with version
     * 1.2.0 these files are only searched in the folders mentioned by
     * {@code #resourceFolders}
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
     * If set to true the apis jar will only contain api which is behind
     * the enabled toggles. All other public api is not included. If set to
     * false (the default) all public api is included per region.
     * @since 1.4.26
     */
    @Parameter(defaultValue = "false")
    private boolean toggleApiOnly;

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
     * Source level for javadoc generation
     */
    @Parameter(defaultValue = "8")
    private String javadocSourceLevel;

    /**
     * Optional version to be put into the manifest of the created jars
     *
     * @since 1.2.0
     */
    @Parameter
    private String apiVersion;

    /**
     * Comma separated list of folders where files are renamed.
     *
     * @since 1.2.0
     */
    @Parameter(defaultValue = "META-INF,SLING-INF")
    private String resourceFolders;

    /**
     * Create a license report file. This is the name of that file within the jar
     *
     * @since 1.2.0
     */
    @Parameter
    private String licenseReport;

    /**
     * A artifact patterns to match artifacts without a license. Follows the pattern
     * "groupId:artifactId:type:classifier:version". After the patter a "=" followed
     * by the license information needs to be specified. This information is used in
     * the license report if no license is specified for an artifact.
     *
     * @since 1.2.0
     */
    @Parameter
    private List<String> licenseDefaults;

    /**
     * Header added on top of the license report
     *
     * @since 1.2.0
     */
    @Parameter(defaultValue = "This archive contains files from the following artifacts:")
    private String licenseReportHeader;

    /**
     * Footer added at the bottom of the license report
     *
     * @since 1.2.0
     */
    @Parameter
    private String licenseReportFooter;

    /**
     * If enabled, packages from artifacts which are fully consumed (all public api)
     * are omitted from the api and source jars and a dependency list is generated
     * instead.
     *
     * @since 1.3.0
     */
    @Parameter(defaultValue = "false")
    private boolean useApiDependencies;

    /**
     * Comma separated list of Maven repository lists. If set, and
     * {@link #useApiDependencies} is enabled, then one of the listed repositories
     * must provide the artifact. If it is not set, all artifacts are 
     * used as dependencies if {@link #useApiDependencies} is enabled.
     *
     * @since 1.3.0
     */
    @Parameter
    private String apiRepositoryUrls;

    /**
     * If this is set to {@code false} the javadoc generated will always contain
     * all APIs even the api from dependencies (if {@link #useApiDependencies}) is
     * enabled. If this is set to {@code true} the javadoc will not contain the API
     * from dependencies and exactly match the binary and source jars.
     * @since 1.5.2
     */
    @Parameter(defaultValue = "false")
    private boolean useApiDependenciesForJavadoc;
    
    /**
     * If {@link #useApiDependencies} is set to {@code true} and {@link #useApiDependenciesForJavadoc}
     * is set to {@code true} this can be set to {@code false} to generate an additional
     * javadoc API jar with all javadoc including the API from dependencies.
     * @since 1.5.2 
     */
    @Parameter(defaultValue = "false")
    private boolean generateJavadocForAllApi;

    /**
     * Fail the build if errors are detected. For example, errors could be missing
     * packages in the api jars, or too many packages in those jars.
     *
     * @since 1.3.2
     */
    @Parameter(defaultValue = "false")
    private boolean failOnError;

    /**
     * specify the manifest properties values that you need to replace in the
     * Manifest file.
     *
     * @since 1.3.2
     */
    @Parameter
    private final Properties manifestProperties = new Properties();

    /**
     * Fail the build if sources are mising for javadoc generation
     *
     * @since 1.3.6
     */
    @Parameter(defaultValue = "false")
    private boolean failOnMissingSourcesForJavadoc;

    /**
     * Whether the index should be generated
     *
     * @since 1.3.6
     */
    @Parameter(defaultValue = "true")
    private boolean javadocIndex;

    /**
     * Whether the tree should be generated
     *
     * @since 1.3.6
     */
    @Parameter(defaultValue = "true")
    private boolean javadocTree;

    /**
     * A artifact patterns to match artifacts put on the javadoc classpath. Follows
     * the pattern "groupId:artifactId:type:classifier:version". Any matching
     * artifact is removed from the classpath. Removals are processed first.
     *
     * @since 1.3.14
     */
    @Parameter
    private List<String> javadocClasspathRemovals;

    /**
     * A artifact patterns to match artifacts put on the javadoc classpath. Follows
     * the pattern "groupId:artifactId:type:classifier:version". From the matching
     * artifacts, only the highest version is kept per artifact. This rule is
     * applied after the removals.
     *
     * @since 1.3.14
     */
    @Parameter
    private List<String> javadocClasspathHighestVersions;

    /**
     * A artifact patterns to match artifacts put on the javadoc classpath. Follows
     * the pattern "groupId:artifactId:type:classifier:version". Any matching
     * artifact is put at the top of the classpath. This rule is applied last.
     *
     * @since 1.3.14
     */
    @Parameter
    private List<String> javadocClasspathTops;

    /**
     * A comma separated list of enabled toggles used as input to generate the api
     * jars.
     *
     * @since 1.5.0
     */
    @Parameter(property = "enabled.toggles")
    private String enabledToggles;

    /**
     * A list of configurations to add additional artifacts to a region
     * for javadoc generation. The value is a comma separated list of extension names
     * from the feature model. If such an extension exists, it must be of type artifacts.
     * The list of names can be prefixed with a region name separated by a colon from
     * the list of names. In that case, the artifacts are only added to that region.
     *
     * @since 1.5.0
     */
    @Parameter
    private List<String> javadocAdditionalExtensions;

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

    /** Artifact Provider. */
    private final ArtifactProvider artifactProvider = new BaseArtifactProvider();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkPreconditions();

        getLog().debug("Retrieving feature files...");
        final Collection<Feature> features = this.getSelectedFeatures(selection).values();

        if (features.isEmpty()) {
            getLog().info(
                    "There are no associated feature files in the current project, plugin execution will be skipped");
        } else {
            getLog().debug("Starting APIs JARs creation...");

            for (final Feature feature : features) {
                onFeature(feature);
            }
        }
    }

    /**
     * Create api jars for a feature
     */
    private void onFeature(final Feature feature) throws MojoExecutionException {
        getLog().info(MessageUtils.buffer().a("Creating API JARs for Feature ").strong(feature.getId().toMvnId())
                .a(" ...").toString());

        final RegionSupport regionSupport = new RegionSupport(this.getLog(), this.incrementalApis, this.toggleApiOnly, this.includeRegions, this.excludeRegions);
        final ApiRegions regions = regionSupport.getApiRegions(feature);
        if (regions == null) {
            // wrongly configured api regions - skip execution, info is logged already so we
            // can just return
            return;
        }

        // create an output directory per feature
        final File featureDir = new File(mainOutputDir, feature.getId().getArtifactId());
        final ApisJarContext ctx = new ApisJarContext(this.mainOutputDir, feature);
        ctx.getConfig().setLicenseDefaults(this.licenseDefaults);
        ctx.getConfig().setLicenseReport(this.licenseReport);
        ctx.getConfig().setLicenseReportHeader(this.licenseReportHeader);
        ctx.getConfig().setLicenseReportFooter(this.licenseReportFooter);
        ctx.getConfig().setJavadocLinks(this.javadocLinks);
        ctx.getConfig().setJavadocClasspathRemovals(this.javadocClasspathRemovals);
        ctx.getConfig().setJavadocClasspathHighestVersions(this.javadocClasspathHighestVersions);
        ctx.getConfig().setJavadocClasspathTops(this.javadocClasspathTops);
        ctx.getConfig().setApiVersion(this.apiVersion);
        ctx.getConfig().setJavadocSourceLevel(this.javadocSourceLevel);
        ctx.getConfig().setBundleResourceFolders(this.resourceFolders);
        ctx.getConfig().setBundleResources(this.includeResources);
        ctx.getConfig().setClassifierMappings(apiClassifierMappings);
        ctx.getConfig().setRegionMappings(apiRegionNameMappings);
        ctx.getConfig().setManifestEntries(manifestProperties);
        ctx.getConfig().setEnabledToggles(this.enabledToggles);
        ctx.getConfig().setAdditionalJavadocExtensions(this.javadocAdditionalExtensions);

        ctx.getConfig().setUseApiDependencies(this.useApiDependencies);
        ctx.getConfig().setUseApiDependenciesForJavadoc(this.useApiDependenciesForJavadoc);
        ctx.getConfig().setGenerateJavadocForAllApi(this.generateJavadocForAllApi);
        ctx.getConfig().setDependencyRepositories(this.apiRepositoryUrls);

        ctx.getConfig().logConfiguration(getLog());

        // check additional extension configuration first to fail fast
        if ( this.generateJavadocJar ) {
            for (final ApiRegion apiRegion : regions.listRegions()) {
                final String regionName = apiRegion.getName();

                final List<Artifact> artifacts = ApisUtil.getAdditionalJavadocArtifacts(ctx, regionName);
                for(final Artifact artifact : artifacts ) {
                    if ( ctx.getFeature().getBundles().getExact(artifact.getId() ) != null ) {
                        throw new MojoExecutionException("Additional javadoc artifact is also listed as a bundle " + artifact.getId().toMvnId());
                    }
                }
            }
        }

        // for each bundle included in the feature file and record directories
        for (final Artifact artifact : feature.getBundles()) {
            onArtifact(regions, ctx, regionSupport, artifact);
        }

        final List<ArtifactInfo> additionalInfos = new ArrayList<>();
        if ( this.generateJavadocJar ) {
            for (final ApiRegion apiRegion : regions.listRegions()) {
                additionalInfos.addAll(getAdditionalJavadocArtifacts(ctx, apiRegion, regionSupport));  
            }
        }

        // sources report
        final List<ArtifactInfo> allInfos = new ArrayList<>(ctx.getArtifactInfos());
        allInfos.addAll(additionalInfos);
        final File sourcesReport = new File(mainOutputDir, this.project.getArtifactId().concat("-sources-report.txt"));
        ApisUtil.writeSourceReport(this.generateSourceJar || this.generateJavadocJar, getLog(), sourcesReport, allInfos);

        boolean hasErrors = false;

        // recollect and package stuff per region
        for (final ApiRegion apiRegion : regions.listRegions()) {
            final String regionName = apiRegion.getName();
            final List<String> report = new ArrayList<>();

            final File regionDir = new File(featureDir, regionName);

            if (generateApiJar) {
                final Collection<ArtifactInfo> infos = ctx.getArtifactInfos(regionName, ctx.getConfig().isUseApiDependencies());
                this.runProcessor(ctx, apiRegion, ArtifactType.APIS, this.apiResources, infos);
                final File apiJar = createArchive(ctx, apiRegion, ArtifactType.APIS, this.apiResources, infos, report);
                report(ctx, apiJar, ArtifactType.APIS, regionSupport, apiRegion, ctx.getConfig().isUseApiDependencies(), report, null);
            }

            // run processor on sources
            if ( generateSourceJar || generateJavadocJar ) {
                final List<ArtifactInfo> infos = new ArrayList<>(ctx.getArtifactInfos(regionName, false));
                if ( generateJavadocJar ) {
                    infos.addAll(getAdditionalJavadocArtifacts(ctx, apiRegion, regionSupport));
                }
                this.runProcessor(ctx, apiRegion, ArtifactType.SOURCES, this.apiResources, infos);
            }

            if (generateSourceJar) {
                final Collection<ArtifactInfo> infos = ctx.getArtifactInfos(regionName, ctx.getConfig().isUseApiDependencies());
                final File sourceJar = createArchive(ctx, apiRegion, ArtifactType.SOURCES, this.apiSourceResources, infos, report);
                report(ctx, sourceJar, ArtifactType.SOURCES, regionSupport, apiRegion, ctx.getConfig().isUseApiDependencies(), report, null);
            }

            if (ctx.getConfig().isUseApiDependencies() && (this.generateApiJar || this.generateSourceJar)) {
                this.createDependenciesFile(ctx, apiRegion);
            }

            if (generateJavadocJar) {
                final File javadocsDir = new File(regionDir, ArtifactType.JAVADOC.getId());
                final ExecutionEnvironmentExtension ext = ExecutionEnvironmentExtension
                        .getExecutionEnvironmentExtension(feature);
                final JavadocLinks links = new JavadocLinks();
                links.calculateLinks(ctx.getConfig().getJavadocLinks(), ctx.getArtifactInfos(regionName, false),
                        ext != null ? ext.getFramework() : null);

                final Collection<ArtifactInfo> infos = generateJavadoc(ctx, regionName, links, javadocsDir, regionSupport, ctx.getConfig().isUseApiDependenciesForJavadoc());
                ctx.setJavadocDir(javadocsDir);
                final File javadocJar = createArchive(ctx, apiRegion, ArtifactType.JAVADOC,
                        this.apiJavadocResources, infos, report);
                report(ctx, javadocJar, ArtifactType.JAVADOC, regionSupport, apiRegion, ctx.getConfig().isUseApiDependenciesForJavadoc(), report, links);

                if ( ctx.getConfig().isUseApiDependencies() && ctx.getConfig().isGenerateJavadocForAllApi() ) {
                    final File javadocsAllDir = new File(regionDir, ArtifactType.JAVADOC_ALL.getId());
                    final Collection<ArtifactInfo> infosForAll = generateJavadoc(ctx, regionName, links, javadocsAllDir, regionSupport, false);
                    ctx.setJavadocDir(javadocsAllDir);
                    final File javadocAllJar = createArchive(ctx, apiRegion, ArtifactType.JAVADOC_ALL,
                            this.apiJavadocResources, infosForAll, report);
                    report(ctx, javadocAllJar, ArtifactType.JAVADOC, regionSupport, apiRegion, false, report, links);    
                }
            }

            // write dependency report
            final ArtifactId dependencyReportId = this.buildArtifactId(ctx, apiRegion, ArtifactType.DEPENDENCY_REPORT);
            final File dependencyReportFile = new File(mainOutputDir, dependencyReportId.toMvnName());
            if ( this.useApiDependencies ) {
                final List<String> output = new ArrayList<>();
                for(final ArtifactInfo info : ctx.getArtifactInfos(regionName, false)) {
                    if ( !info.isUseAsDependencyPerRegion(regionName) && !"".equals(info.getNotUseAsDependencyPerRegionReason(regionName))) {
                        output.add("- ".concat(info.getId().toMvnId()).concat(" : ").concat(info.getNotUseAsDependencyPerRegionReason(regionName)));
                    }
                }
                Collections.sort(output);
                if ( output.isEmpty() ) {
                    output.add("All artifacts are used as a dependency");
                } else {
                    output.add(0, "The following artifacts are not used as a dependency:");
                }
                output.stream().forEach(msg -> getLog().info(msg));
                try {
                    Files.write(dependencyReportFile.toPath(), output);
                } catch (final IOException e) {
                    throw new MojoExecutionException("Unable to write " + dependencyReportFile, e);
                }
             } else {
                if ( dependencyReportFile.exists() ) {
                    dependencyReportFile.delete();
                }
            }
            
            // write report
            final ArtifactId reportId = this.buildArtifactId(ctx, apiRegion, ArtifactType.REPORT);
            final File reportFile = new File(mainOutputDir, reportId.toMvnName());
            if (!report.isEmpty()) {
                report.stream().forEach(v -> getLog().info(v));
                try {
                    Files.write(reportFile.toPath(), report);
                } catch (final IOException e) {
                    throw new MojoExecutionException("Unable to write " + reportFile, e);
                }
                hasErrors = true;
            } else {
                if (reportFile.exists()) {
                    reportFile.delete();
                }
            }
        }

        if (hasErrors && this.failOnError) {
            throw new MojoExecutionException("API generation has errors, please see report files for more information");
        }

        getLog().info(MessageUtils.buffer().a("APIs JARs for Feature ").project(feature.getId().toMvnId())
                .a(" succesfully created").toString());
    }

    private void report(final ApisJarContext ctx, 
            final File jarFile, 
            final ArtifactType artifactType,
            final RegionSupport regionSupport,
            final ApiRegion apiRegion, 
            final boolean omitDependencyArtifacts, 
            final List<String> report,
            final JavadocLinks links) throws MojoExecutionException {
        final Map.Entry<Set<String>, Set<String>> packageResult = ApisUtil.getPackages(ctx, jarFile,
                artifactType.getContentExtension());
        final Set<String> apiPackages = packageResult.getKey();
        final Set<String> otherPackages = packageResult.getValue();
        if (omitDependencyArtifacts) {
            for (final ArtifactInfo info : ctx.getArtifactInfos(apiRegion.getName(), false)) {
                if (info.isUseAsDependencyPerRegion(apiRegion.getName())) {
                    for (final Clause c : info.getUsedExportedPackages(apiRegion.getName())) {
                        apiPackages.add(c.getName());
                    }
                }
            }
        }
        // make sure no reports for packages not containing java classes
        otherPackages.addAll(ctx.getPackagesWithoutJavaClasses());
        // ignore packages without sources for javadoc?
        if (artifactType == ArtifactType.JAVADOC && !failOnMissingSourcesForJavadoc) {
            otherPackages.addAll(ctx.getPackagesWithoutSources());
        }
        // add packages found in links
        if (links != null) {
            apiPackages.addAll(links.getLinkedPackages());
        }
        final List<ApiExport> missing = new ArrayList<>();

        for (final ApiExport exp : regionSupport.getAllExports(apiRegion, ctx.getConfig().getEnabledToggles())) {
            final String packageName = exp.getName();
            if (!apiPackages.remove(packageName) && !otherPackages.remove(packageName)) {
                missing.add(exp);
            }
        }
        // correct remaining packages
        if (links != null) {
            apiPackages.removeAll(links.getLinkedPackages());
        }
        if (artifactType == ArtifactType.JAVADOC && !omitDependencyArtifacts) {
            otherPackages.removeAll(ctx.getPackagesWithoutSources());
            // handle additional artifacts
            for(final Artifact artifact : ApisUtil.getAdditionalJavadocArtifacts(ctx, apiRegion.getName()) ) {
                final ArtifactInfo info = ctx.getArtifactInfo(artifact.getId());
                if ( info != null ) {
                    for(final Clause clause : info.getUsedExportedPackages(apiRegion.getName())) {
                        if ( !apiPackages.remove(clause.getName()) ) {
                            final ApiExport export = new ApiExport(clause.getName());
                            missing.add(export);
                        }
                    }
                }
            }
        }
        otherPackages.removeAll(ctx.getPackagesWithoutJavaClasses());

        apiPackages.addAll(otherPackages);
        if (artifactType == ArtifactType.JAVADOC) {
            // jquery might be part of javadoc
            final Collection<String> jqueryPackages = Arrays.asList("jquery", "jquery.external.jquery", "jquery.images",
                    "jquery.jszip-utils.dist", "jquery.jszip.dist", "resources");
            apiPackages.removeAll(jqueryPackages);
        }

        if (missing.isEmpty() && apiPackages.isEmpty()) {
            getLog().info("Verified " + artifactType.getId() + " jar for region " + apiRegion.getName());
        } else {
            Collections.sort(missing);
            report.add(artifactType.getId().concat(" jar for region ").concat(apiRegion.getName()).concat(" has ")
                    .concat(String.valueOf(missing.size() + apiPackages.size())).concat(" errors:"));
            for (final ApiExport exp : missing) {
                final List<String> candidates = new ArrayList<>();
                for (final ArtifactInfo info : ctx.getArtifactInfos()) {
                    for (final Clause clause : info.getUsedExportedPackages(apiRegion.getName())) {
                        if (exp.getName().equals(clause.getName())) {
                            candidates.add(info.getId().toMvnName());
                            break;
                        }
                    }
                }
                report.add("- Missing package ".concat(exp.getName()).concat(" from bundle(s) ")
                        .concat(String.join(",", candidates)));
            }
            for (final String m : apiPackages) {
                report.add("- Unwanted package ".concat(m));
            }
        }
    }

    private File getArtifactFile(final ArtifactId artifactId)
            throws MojoExecutionException {
        final URL artifactURL = retrieve(artifactId);
        if (artifactURL == null) {
            throw new MojoExecutionException("Unable to find artifact " + artifactId.toMvnId());
        }
        File bundleFile = null;
        try {
            bundleFile = IOUtils.getFileFromURL(artifactURL, true, this.getTmpDir());
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage());
        }

        return bundleFile;
    }

    /**
     * Process a single artifact. This is a "global" processing and not per region
     *
     * @param ctx      The context
     * @param artifact The artifact
     * @throws MojoExecutionException
     */
    private void onArtifact(final ApiRegions apiRegions,
            final ApisJarContext ctx,
            final RegionSupport regionSupport,
            Artifact artifact) throws MojoExecutionException {
        final File bundleFile = getArtifactFile(artifact.getId());

        final Manifest manifest = regionSupport.getManifest(artifact.getId(), bundleFile);

        // check if the bundle is exporting packages?
        final Clause[] exportedPackageClauses = regionSupport.getExportedPackages(manifest);
        if (exportedPackageClauses.length > 0) {

            // calculate the exported packages in the manifest file for all regions
            final Set<String> usedExportedPackages = regionSupport.computeAllUsedExportPackages(apiRegions, ctx.getConfig().getEnabledToggles(), exportedPackageClauses, artifact);

            if (!usedExportedPackages.isEmpty()) {
                // check for previous version of artifact due to toggles
                ArtifactId previous = null;
                for (final String pckName : usedExportedPackages) {
                    for (final ApiRegion region : apiRegions.listRegions()) {
                        final ApiExport exp = region.getExportByName(pckName);
                        if (exp != null) {
                            if (exp.getToggle() != null
                                    && !ctx.getConfig().getEnabledToggles().contains(exp.getToggle())
                                    && exp.getPreviousPackageVersion() != null) {
                                if (previous != null && previous.compareTo(exp.getPreviousArtifactId()) != 0) {
                                    throw new MojoExecutionException(
                                            "More than one previous version artifact configured for "
                                                    + artifact.getId().toMvnId() + " : " + previous.toMvnId() + ", "
                                                    + exp.getPreviousArtifactId().toMvnId());
                                }
                                previous = exp.getPreviousArtifactId();
                            }
                            break;
                        }
                    }
                }
                if (previous != null) {
                    final Artifact previousArtifact = new Artifact(previous);
                    previousArtifact.getMetadata().putAll(artifact.getMetadata());
                    getLog().debug("Using " + previous.toMvnId() + " instead of " + artifact.getId().toMvnId()
                            + " due to disabled toggle(s)");
                    artifact = previousArtifact;
                }

                final ArtifactInfo info = ctx.addArtifactInfo(artifact);
                info.setUsedExportedPackages(usedExportedPackages);

                // calculate per region packages
                for (final ApiRegion region : apiRegions.listRegions()) {
                    final Set<Clause> usedExportedPackagesPerRegion = regionSupport.computeUsedExportPackagesPerRegion(region,
                            exportedPackageClauses, usedExportedPackages);

                    // check whether packages are included in api jars - or added as a dependency
                    String useAsDependency = "";
                    if ( ctx.getConfig().isUseApiDependencies() ) {
                        useAsDependency = regionSupport.calculateOmitDependenciesFlag(region, exportedPackageClauses,
                                     usedExportedPackagesPerRegion);
                    }
                    if (useAsDependency == null ) {
                        if (ctx.findDependencyArtifact(getLog(), info)) {                            
                            // check scm info
                            if (artifact.getMetadata().get(ApisUtil.SCM_LOCATION) != null) {
                                throw new MojoExecutionException("Dependency artifact must not specify "
                                        + ApisUtil.SCM_LOCATION + " : " + artifact.getId().toMvnId());
                            }
                        } else {
                            useAsDependency = "Unable to find artifact in maven repository.";
                        }
                    }
                    info.setUsedExportedPackages(region.getName(), usedExportedPackagesPerRegion, useAsDependency);
                }

                info.setBinDirectory(new File(ctx.getDeflatedBinDir(), info.getId().toMvnName()));
                info.setSourceDirectory(new File(ctx.getDeflatedSourcesDir(), info.getId().toMvnName()));

                final boolean skipBinDeflate = info.getBinDirectory().exists();
                if (skipBinDeflate) {
                    getLog().debug("Artifact " + info.getId().toMvnName() + " already deflated");
                }
                final boolean skipSourceDeflate = info.getSourceDirectory().exists();
                if (skipSourceDeflate) {
                    getLog().debug("Source for artifact " + info.getId().toMvnName() + " already deflated");
                }

                final String bundleClassPath = manifest.getMainAttributes().getValue(Constants.BUNDLE_CLASSPATH);
                final String[] embeddedBundles;
                if (bundleClassPath != null && !bundleClassPath.isEmpty()) {
                    embeddedBundles = bundleClassPath.split(",");
                } else {
                    embeddedBundles = null;
                }

                processBinary(ctx, info, bundleFile, artifact, embeddedBundles, skipBinDeflate, skipSourceDeflate);

                // check if the bundle wraps other bundles
                if (embeddedBundles != null) {
                    computeWrappedBundles(ctx, info, embeddedBundles, skipBinDeflate, skipSourceDeflate);
                }

                postProcessArtifact(ctx, info, artifact);

                if (!info.getSourceDirectory().exists()) {
                    info.setSourceDirectory(null);
                }

                if (generateJavadocJar) {
                    ApisUtil.buildJavadocClasspath(getLog(), repositorySystem, mavenSession, artifact.getId())
                            .forEach(ctx::addJavadocClasspath);
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
     *
     * @param ctx  The context
     * @param info The artifact info
     * @throws MojoExecutionException
     */
    private void postProcessArtifact(final ApisJarContext ctx, final ArtifactInfo info, final Artifact artifact)
            throws MojoExecutionException {
        // binary post processing
        this.postProcessBinDirectory(ctx, info, info.getBinDirectory(), "");

        // source post processing
        if ((generateSourceJar || generateJavadocJar)) {
            final Set<String> foundPackages = new HashSet<>();
            if (info.getSourceDirectory() != null && info.getSourceDirectory().exists()) {
                final String encoding = artifact.getMetadata().getOrDefault(ApisUtil.SCM_ENCODING, "UTF-8");
                this.postProcessSourcesDirectory(ctx, info, foundPackages, info.getSourceDirectory(),
                        "UTF-8".equals(encoding) ? null : encoding, "");
            }
            // check for missing packages
            for (final String pck : info.getUsedExportedPackages()) {
                if (!foundPackages.contains(pck)) {
                    // We need to record this kind of packages and ensure we don't trigger warnings
                    // for them
                    // when checking the api jars for correctness.
                    getLog().debug("No sources found in " + pck);
                    ctx.getPackagesWithoutSources().add(pck);
                }
            }
        }

    }

    private void postProcessBinDirectory(final ApisJarContext ctx, final ArtifactInfo info, final File dir,
            final String pck) {
        boolean hasJavaFile = false;
        for (final File child : dir.listFiles()) {
            if (child.isDirectory()) {
                postProcessBinDirectory(ctx, info, child,
                        pck.isEmpty() ? child.getName() : pck.concat(".").concat(child.getName()));
            } else if (child.getName().endsWith(ArtifactType.APIS.getContentExtension())) {
                hasJavaFile = true;
            }
        }
        if (dir.listFiles().length == 0 && !pck.isEmpty()) {
            // empty dir -> remove
            dir.delete();
        } else if (!hasJavaFile && info.getUsedExportedPackages().contains(pck)) {

            // We need to record this kind of packages and ensure we don't trigger warnings
            // for them
            // when checking the api jars for correctness.
            getLog().debug("No classes found in " + pck);
            ctx.getPackagesWithoutJavaClasses().add(pck);
        }
    }

    /**
     * Process a binary Extract the binary, rename resources and (optional) download
     * the sources
     *
     * @param ctx               The context
     * @param info              The current artifact
     * @param binFile           The binary to extract
     * @param binArtifact       The artifact to extract
     * @param embeddedBundles   Embedded bundles (optional)
     * @param skipBinDeflate    Flag to skip deflating the binary
     * @param skipSourceDeflate Flag to skip deflating the source
     * @throws MojoExecutionException
     */
    private void processBinary(final ApisJarContext ctx, final ArtifactInfo info, final File binFile,
            final Artifact binArtifact, final String[] embeddedBundles, final boolean skipBinDeflate,
            final boolean skipSourceDeflate) throws MojoExecutionException {
        if (!skipBinDeflate) {
            // deflate all bundles first, in order to copy APIs and resources later,
            // depending to the region
            final List<String> deflateIncludes = new ArrayList<>();

            // add all used exported packages
            deflateIncludes.addAll(Arrays.asList(info.getUsedExportedPackageIncludes()));
            // add embedded bundles
            if (embeddedBundles != null) {
                for (final String jarName : embeddedBundles) {
                    if (!".".equals(jarName)) {
                        deflateIncludes.add(jarName);
                    }
                }
            }
            // add resources from the folders
            deflateIncludes.addAll(getIncludeResourcePatterns(ctx, info.getId()));

            // deflate
            this.deflate(info.getBinDirectory(), binFile, deflateIncludes.toArray(new String[deflateIncludes.size()]));

        }
        // renaming potential name-collapsing resources
        this.renameResources(ctx, info, binArtifact.getId());

        // download sources
        if (this.generateSourceJar || this.generateJavadocJar) {
            if (!skipSourceDeflate) {
                this.downloadSources(ctx, info, binArtifact);
            } else {
                info.addSourceInfo("USE CACHE FROM PREVIOUS BUILD");
            }
        }

    }

    private List<String> getIncludeResourcePatterns(final ApisJarContext ctx, final ArtifactId id) {
        final List<String> pattern = new ArrayList<>();
        for (final String folder : ctx.getConfig().getBundleResourceFolders()) {
            for (final String inc : ctx.getConfig().getBundleResources()) {
                pattern.add(folder.concat("/").concat(inc));
            }
        }

        // add NOTICE and LICENSE for license report
        if (ctx.getConfig().getLicenseReport() != null) {
            final String licenseDefault = ctx.getConfig().getLicenseDefault(id);
            if (licenseDefault == null || !licenseDefault.isEmpty()) {
                pattern.add("META-INF/NOTICE");
                pattern.add("META-INF/LICENSE");
            }
        }

        return pattern;
    }

    private void postProcessSourcesDirectory(final ApisJarContext ctx, final ArtifactInfo info,
            final Set<String> foundPackages, final File dir, final String readEncoding, final String pck)
            throws MojoExecutionException {
        boolean hasSourceFile = false;
        for (final File child : dir.listFiles()) {
            if (child.isDirectory()) {
                postProcessSourcesDirectory(ctx, info, foundPackages, child, readEncoding,
                        pck.isEmpty() ? child.getName() : pck.concat(".").concat(child.getName()));
            } else if (child.getName().endsWith(ArtifactType.SOURCES.getContentExtension())) {
                hasSourceFile = true;
                if (readEncoding != null) {
                    try {
                        final String javaSource = FileUtils.fileRead(child, readEncoding);
                        FileUtils.fileWrite(child, StandardCharsets.UTF_8.name(), javaSource);
                    } catch (final IOException ioe) {
                        throw new MojoExecutionException("Unable to clean up java source " + child, ioe);
                    }
                }
            }
        }
        if (dir.listFiles().length == 0 && !pck.isEmpty()) {
            // empty dir -> remove
            dir.delete();
        } else if (hasSourceFile) {
            foundPackages.add(pck);
        }
    }

    private void computeWrappedBundles(final ApisJarContext ctx, final ArtifactInfo info,
            final String[] embeddedBundles, final boolean skipBinDeflate, final boolean skipSourceDeflate)
            throws MojoExecutionException {
        for (final String jarName : embeddedBundles) {
            if (".".equals(jarName)) {
                continue;
            }

            final File wrappedJar = new File(info.getBinDirectory(), jarName);
            getLog().debug("Processing wrapped bundle " + wrappedJar);

            final Properties properties = new Properties();

            try (final JarInputStream jis = new JarInputStream(new FileInputStream(wrappedJar))) {
                JarEntry jarEntry = null;
                while ((jarEntry = jis.getNextJarEntry()) != null) {
                    if (!jarEntry.isDirectory() && pomPropertiesPattern.matcher(jarEntry.getName()).matches()) {
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
                final File bundleFile = getArtifactFile(syntheticArtifact.getId());

                processBinary(ctx, info, bundleFile, syntheticArtifact, null, skipBinDeflate, skipSourceDeflate);
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
        if (synthesized.length() < bundleName.length() && bundleName.startsWith(synthesized)) {
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

    private URL retrieve(final ArtifactId artifactId) {
        getLog().debug("Retrieving artifact " + artifactId + "...");
        URL sourceFile = artifactProvider.provide(artifactId);
        if (sourceFile != null) {
            getLog().debug("Artifact " + artifactId + " successfully retrieved : " + sourceFile);
        }
        return sourceFile;
    }

    private void deflate(final File destDirectory, final File artifact, final String... includes)
            throws MojoExecutionException {
        getLog().debug("Deflating artifact " + artifact.getName() + "...");
        destDirectory.mkdirs();

        // unarchive the bundle
        try {
            final UnArchiver unArchiver = archiverManager.getUnArchiver(artifact);
            unArchiver.setSourceFile(artifact);
            unArchiver.setDestDirectory(destDirectory);
            final IncludeExcludeFileSelector selector = new IncludeExcludeFileSelector();
            selector.setIncludes(includes);
            unArchiver.setFileSelectors(new FileSelector[] { selector });
            unArchiver.setOverwrite(false);
            unArchiver.extract();
        } catch (final NoSuchArchiverException e) {
            throw new MojoExecutionException(
                    "An error occurred while deflating file " + artifact + " to directory " + destDirectory, e);
        }

        getLog().debug("Artifact " + artifact + " successfully deflated");
    }

    private void renameResources(final ApisJarContext ctx, final ArtifactInfo info, final ArtifactId artifactId)
            throws MojoExecutionException {
        final List<String> patterns = getIncludeResourcePatterns(ctx, info.getId());
        if (patterns.isEmpty()) {
            getLog().debug("No configured resources to rename in " + info.getBinDirectory());
        }

        getLog().debug("Renaming " + patterns + " files in " + info.getBinDirectory() + "...");

        final DirectoryScanner directoryScanner = new DirectoryScanner();
        directoryScanner.setBasedir(info.getBinDirectory());
        directoryScanner.setIncludes(patterns.toArray(new String[patterns.size()]));
        directoryScanner.scan();

        if (directoryScanner.getIncludedFiles().length == 0) {
            getLog().debug("No " + patterns + " resources in " + info.getBinDirectory() + " to be renamed found.");
            return;
        }

        for (final String resourceName : directoryScanner.getIncludedFiles()) {
            final File resource = new File(info.getBinDirectory(), resourceName);

            String includedName = resourceName.replace(File.separatorChar, '/');
            if (!info.getIncludedResources().contains(resource)) {
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
                    final int lastSlash = includedName.lastIndexOf('/');
                    if (lastSlash == -1) {
                        includedName = renamed.getName();
                    } else {
                        includedName = includedName.substring(0, lastSlash + 1).concat(renamed.getName());
                    }
                }
            }
            if (includedName.endsWith(ArtifactType.CND.getContentExtension())) {
                info.getNodeTypes().add(includedName);
            }
        }

        getLog().debug(patterns + " resources in " + info.getBinDirectory() + " successfully renamed");
    }

    private boolean downloadSourceAndDeflate(final ApisJarContext ctx, final ArtifactInfo info,
            final ArtifactId sourcesArtifactId, final boolean allowFallback) throws MojoExecutionException {
        boolean failed = false;
        try {
            final URL url = retrieve(sourcesArtifactId);
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

    private void downloadSources(final ApisJarContext ctx, final ArtifactInfo info, final Artifact artifact)
            throws MojoExecutionException {
        getLog().debug("Downloading sources for " + artifact.getId().toMvnId() + "...");

        ApisUtil.validateSourceInfo(artifact);

        final List<ArtifactId> scmIds = ApisUtil.getSourceIds(artifact);
        final String scmLocation = artifact.getMetadata().get(ApisUtil.SCM_LOCATION);
        if (scmIds != null) {
            for (final ArtifactId sourcesArtifactId : scmIds) {
                downloadSourceAndDeflate(ctx, info, sourcesArtifactId, false);
                info.addSourceInfo(sourcesArtifactId);
            }
        } else if (scmLocation != null) {
            final String connection = checkoutSourcesFromSCM(ctx, info, artifact);
            info.addSourceInfo(connection);
        } else {
            String sourceClassifier = artifact.getMetadata().get(ApisUtil.SCM_CLASSIFIER);
            if (sourceClassifier == null) {
                sourceClassifier = "sources"; // default
            }
            final ArtifactId sourcesArtifactId = artifact.getId().changeClassifier(sourceClassifier).changeType("jar");
            if (downloadSourceAndDeflate(ctx, info, sourcesArtifactId,
                    artifact.getMetadata().get(ApisUtil.SCM_CLASSIFIER) == null)) {
                final String connection = checkoutSourcesFromSCM(ctx, info, artifact);
                info.addSourceInfo(connection);
            } else {
                info.addSourceInfo(sourcesArtifactId);
            }
        }
    }

    private Model getArtifactPom(final ApisJarContext ctx, final ArtifactId artifactId) throws MojoExecutionException {
        final ArtifactId pomArtifactId = artifactId.changeClassifier(null).changeType("pom");
        // check model cache
        Model model = ctx.getModelCache().get(pomArtifactId);
        if (model == null) {
            getLog().debug("Retrieving POM " + pomArtifactId.toMvnId() + "...");
            // POM file must exist, let the plugin fail otherwise
            final URL pomURL = retrieve(pomArtifactId);
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
            model = modelBuilder.buildRawModel(pomFile, ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL, false).get();
            getLog().debug("POM model " + pomArtifactId.toMvnId() + " successfully read");

            // cache model
            ctx.getModelCache().put(pomArtifactId, model);
        }
        return model;
    }

    private String checkoutSourcesFromSCM(final ApisJarContext ctx, final ArtifactInfo info,
            final Artifact sourceArtifact) throws MojoExecutionException {
        // fallback to Artifacts SCM metadata first
        String connection = sourceArtifact.getMetadata().get(ApisUtil.SCM_LOCATION);
        String tag = sourceArtifact.getMetadata().get(ApisUtil.SCM_TAG);

        // Artifacts SCM metadata may not available or are an override, let's fallback
        // to the POM
        getLog().debug("Falling back to SCM checkout...");
        final Model pomModel = getArtifactPom(ctx, sourceArtifact.getId());
        getLog().debug("Processing SCM info from pom...");

        final Scm scm = pomModel.getScm();
        if (scm != null) {
            if (connection == null) {
                connection = scm.getConnection();
            }
            if (tag == null) {
                tag = scm.getTag();
                // Maven uses "HEAD" as default value
                if ( "HEAD".equals(tag) ) {
                    tag = null;
                }
            }
        }

        if (connection == null) {
            getLog().warn("Ignoring sources for artifact " + sourceArtifact.getId().toMvnId() + " : SCM not defined in "
                    + sourceArtifact.getId().toMvnId() + " bundle neither in " + pomModel.getId() + " POM file.");
            return null;
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
                    if (scmVersion == null) {
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
                    return null;
                }
            }

            // retrieve the exact pom location to detect the bundle path in the repo
            DirectoryScanner pomScanner = new DirectoryScanner();
            pomScanner.setBasedir(basedir);
            pomScanner.setIncludes("**/pom.xml");
            pomScanner.scan();
            for (String pomFileLocation : pomScanner.getIncludedFiles()) {
                final File pomFile = new File(basedir, pomFileLocation);
                final Model model = modelBuilder
                        .buildRawModel(pomFile, ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL, false).get();

                if (sourceArtifact.getId().getArtifactId().equals(model.getArtifactId())) {
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
                    getLog().warn(
                            "Ignoring sources for artifact " + sourceArtifact.getId().toMvnId() + " : SCM checkout for "
                                    + sourceArtifact.getId().toMvnId() + " does not contain any source.");
                    return null;
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

            return tag == null ? connection : connection.concat("@").concat(tag);
        } catch (ScmRepositoryException se) {
            throw new MojoExecutionException("An error occurred while reading SCM from " + connection
                    + " connection for bundle " + sourceArtifact.getId(), se);
        } catch (NoSuchScmProviderException nsspe) {
            getLog().warn("Ignoring sources for artifact " + sourceArtifact.getId().toMvnId()
                    + " : bundle points to an SCM connection " + connection
                    + " which does not specify a valid or supported SCM provider", nsspe);
            return null;
        }
    }

    private String getApiExportClause(final ApiRegion region, final Collection<ArtifactInfo> infos) {
        final StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (final ArtifactInfo info : infos) {
            for (final Clause clause : info.getUsedExportedPackages(region.getName())) {
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

    private void addFileSets(final ApiRegion apiRegion,
             final ArtifactType archiveType,
             final Collection<ArtifactInfo> infos,
             final JarArchiver jarArchiver,
             final List<Source> sources) {
        for (final ArtifactInfo info : infos) {
            final File dir = archiveType == ArtifactType.APIS ? info.getBinDirectory() : info.getSourceDirectory();

            if (dir != null) {
                final String[] usedExportedPackageIncludes = info.getUsedExportedPackageIncludes(apiRegion.getName());
                getLog().debug("Adding directory " + dir.getName() + " with "
                        + Arrays.toString(usedExportedPackageIncludes));
                final DefaultFileSet fileSet = new DefaultFileSet(dir);
                fileSet.setIncludingEmptyDirectories(false);
                fileSet.setIncludes(usedExportedPackageIncludes);

                if ( jarArchiver != null ) {
                    jarArchiver.addFileSet(fileSet);
                }
                if ( sources != null ) {
                    sources.add(new DirectorySource(fileSet));
                }
            }
        }
    }

    private void addResources(final Collection<ArtifactInfo> infos,
             final List<File> resources,
             final JarArchiver jarArchiver,
             final List<Source> sources) {
        for (final ArtifactInfo info : infos) {
            if ( info.getBinDirectory() != null ) {
                final int prefixLength = info.getBinDirectory().getAbsolutePath().length() + 1;
                for (final File resource : info.getIncludedResources()) {
                    final String name = resource.getAbsolutePath().substring(prefixLength);
                    getLog().debug("Adding resource " + name);

                    if ( jarArchiver != null ) {
                        jarArchiver.addFile(resource, name);

                    }
                    if ( sources != null ) {
                        sources.add(new FileSource(info.getBinDirectory(), resource));
                    }
                }
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

                    if ( jarArchiver != null ) {
                        for (String includedFile : ds.getIncludedFiles()) {
                            jarArchiver.addFile(new File(rsrc, includedFile), includedFile);
                        }
                    }
                    if (sources != null) {
                        final DefaultFileSet fileSet = new DefaultFileSet(rsrc);
                        fileSet.setIncludingEmptyDirectories(false);
                        fileSet.setIncludes(new String[] { "**/*.*" });
                        sources.add(new DirectorySource(fileSet));
                    }
                } else {
                    if ( jarArchiver != null ) {
                        jarArchiver.addFile(rsrc, rsrc.getName());
                    }
                    if (sources != null) {
                        sources.add(new FileSource(rsrc.getParentFile(), rsrc));
                    }
                }
            }
        }
    }

    private void runProcessor(final ApisJarContext ctx,
        final ApiRegion apiRegion,
        final ArtifactType archiveType,
        final List<File> resources,
        final Collection<ArtifactInfo> infos) {
        final List<Processor> processors = ApisUtil.getProcessors();
        if ( !processors.isEmpty() ) {
            final List<Source> sources = new ArrayList<>();

            this.addFileSets(apiRegion, archiveType, infos, null, sources);
            this.addResources(infos, resources, null, sources);

            // run processors
            for (final Processor p : processors) {
                final ProcessorContext pc = new ProcessorContext() {

                    @Override
                    public MavenSession getSession() {
                        return mavenSession;
                    }

                    @Override
                    public MavenProject getProject() {
                        return project;
                    }

                    @Override
                    public Feature getFeature() {
                        return ctx.getFeature();
                    }

                    @Override
                    public ApiRegion getApiRegion() {
                        return apiRegion;
                    }

                    @Override
                    public Log getLog() {
                        return ApisJarMojo.this.getLog();
                    }
                };
                if ( archiveType == ArtifactType.APIS ) {
                    getLog().info("Running processor " + p.getName() + " on binaries...");
                    p.processBinaries(pc, sources);
                } else {
                    getLog().info("Running processor " + p.getName() + " on sources...");
                    p.processSources(pc, sources);
                }
            }
        }
    }

    private File createArchive(final ApisJarContext ctx, final ApiRegion apiRegion, final ArtifactType archiveType,
            final List<File> resources, final Collection<ArtifactInfo> infos, final List<String> report)
            throws MojoExecutionException {
        final JarArchiver jarArchiver = new JarArchiver();

        if (archiveType == ArtifactType.APIS || archiveType == ArtifactType.SOURCES) {
            // api or source
            this.addFileSets(apiRegion, archiveType, infos, jarArchiver, null);
        } else {
            // javadoc or javadoc_all
            final DefaultFileSet fileSet = new DefaultFileSet(ctx.getJavadocDir());
            jarArchiver.addFileSet(fileSet);
        }

        // add included resources
        this.addResources(infos, resources, jarArchiver, null);

        // check for license report
        if ( ctx.getConfig().getLicenseReport() != null ) {
            final File out = this.createLicenseReport(ctx, apiRegion, infos, report);
            jarArchiver.addFile(out, ctx.getConfig().getLicenseReport());
        }

        final ArtifactId targetId = this.buildArtifactId(ctx, apiRegion, archiveType);
        final String artifactName = String.format("%s-%s", targetId.getArtifactId(), targetId.getClassifier());

        MavenArchiveConfiguration archiveConfiguration = new MavenArchiveConfiguration();
        archiveConfiguration.setAddMavenDescriptor(false);
        if (archiveType == ArtifactType.APIS) {
            // APIs need OSGi Manifest entry
            String symbolicName = artifactName.replace('-', '.');
            archiveConfiguration.addManifestEntry("Export-Package", getApiExportClause(apiRegion, infos));
            archiveConfiguration.addManifestEntry("Bundle-Description", project.getDescription());
            archiveConfiguration.addManifestEntry("Bundle-Version", targetId.getOSGiVersion().toString());
            archiveConfiguration.addManifestEntry("Bundle-ManifestVersion", "2");
            archiveConfiguration.addManifestEntry("Bundle-SymbolicName", symbolicName);
            archiveConfiguration.addManifestEntry("Bundle-Name", artifactName);

            final Set<String> nodeTypes = new HashSet<>();
            for(final ArtifactInfo info : infos) {
                 nodeTypes.addAll(info.getNodeTypes());
            }
            if (!nodeTypes.isEmpty()) {
                archiveConfiguration.addManifestEntry("Sling-Nodetypes", String.join(",", nodeTypes));
            }
            if (project.getOrganization() != null) {
                archiveConfiguration.addManifestEntry("Bundle-Vendor", project.getOrganization().getName());
            }

            // add provide / require capability to make the jar unresolvable
            archiveConfiguration.addManifestEntry("Provide-Capability", "osgi.unresolvable");
            archiveConfiguration.addManifestEntry("Require-Capability", "osgi.unresolvable;filter:=\"(&(must.not.resolve=*)(!(must.not.resolve=*)))\",osgi.ee;filter:=\"(&(osgi.ee=JavaSE/compact2)(version=1.8))\"");
        }
        archiveConfiguration.addManifestEntry("Implementation-Version", targetId.getVersion());
        archiveConfiguration.addManifestEntry("Specification-Version", targetId.getVersion());

        archiveConfiguration.addManifestEntry("Implementation-Title", artifactName);
        archiveConfiguration.addManifestEntry("Specification-Title", artifactName);
        if (project.getOrganization() != null) {
            archiveConfiguration.addManifestEntry("Implementation-Vendor", project.getOrganization().getName());
            archiveConfiguration.addManifestEntry("Specification-Vendor", project.getOrganization().getName());
        }

        // replace/add manifest entries with the one provided in manifestProperties configuration
        archiveConfiguration.addManifestEntries(ctx.getConfig().getManifestEntries());

        final File target = new File(mainOutputDir, targetId.toMvnName());
        MavenArchiver archiver = new MavenArchiver();
        archiver.setArchiver(jarArchiver);
        archiver.setOutputFile(target);

        try {
            archiver.createArchive(mavenSession, project, archiveConfiguration);
            if (this.attachApiJars) {
                projectHelper.attachArtifact(project, targetId.getType(), targetId.getClassifier(), target);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("An error occurred while creating APIs "
                    + target
                    +" archive", e);
        }

        return target;
    }

    private ArtifactId buildArtifactId(final ApisJarContext ctx, final ApiRegion apiRegion, final ArtifactType artifactType) {
        final StringBuilder classifierBuilder = new StringBuilder();
        if (ctx.getFeatureId().getClassifier() != null) {
            classifierBuilder.append(ctx.getConfig().mapApiClassifier(ctx.getFeatureId().getClassifier()))
                             .append('-');
        }
        final String finalClassifier = classifierBuilder.append(ctx.getConfig().mapApiRegionName(apiRegion.getName()))
                                                  .append('-')
                                                  .append(artifactType.getId())
                                                  .toString();

        return new ArtifactId(this.project.getGroupId(),
                this.project.getArtifactId(),
                ctx.getConfig().getApiVersion() != null ? ctx.getConfig().getApiVersion() : this.project.getVersion(),
                finalClassifier,
                artifactType.getExtension());
    }

    /**
     * Create the dependencies file for a region
     * @param ctx The context
     * @param apiRegion The region
     */
    private void createDependenciesFile(final ApisJarContext ctx, final ApiRegion apiRegion) throws MojoExecutionException {
        final Collection<ArtifactInfo> infos = ctx.getArtifactInfos(apiRegion.getName(), false);

        final List<ArtifactId> dependencies = new ArrayList<>();

        for(final ArtifactInfo info : infos) {
            if ( info.isUseAsDependencyPerRegion(apiRegion.getName()) ) {
                dependencies.addAll(info.getDependencyArtifacts());
            }
        }
        Collections.sort(dependencies);

        final ArtifactId targetId = this.buildArtifactId(ctx, apiRegion, ArtifactType.DEPENDENCIES);
        final File target = new File(mainOutputDir, targetId.toMvnName());

        if ( !dependencies.isEmpty() ) {
            getLog().info("Writing dependencies file ".concat(target.getAbsolutePath()));
            try ( final Writer w = new FileWriter(target)) {
                for(final ArtifactId id : dependencies) {
                    w.write(id.toMvnId());
                    w.write(System.lineSeparator());
                }
            } catch (final IOException e) {
                throw new MojoExecutionException("Unable to write dependencies file", e);
            }
            if (this.attachApiJars) {
                projectHelper.attachArtifact(project, targetId.getType(), targetId.getClassifier(), target);
            }
        } else {
            getLog().info("No dependencies found");
            if ( target.exists() ) {
                target.delete();
            }
        }
    }


    /**
     * Generate the javadoc
     * @param ctx The generation context
     * @param regionName The region name
     * @param links The links used for javadoc generation
     * @param javadocDir The output directory
     * @param regionSupport The region support
     * @param useDependencies Whether dependencies should be used
     * @return A collection of artifacts used for the generation or {@code null} if no packages found
     * @throws MojoExecutionException on error
     */
    private Collection<ArtifactInfo> generateJavadoc(final ApisJarContext ctx,
            final String regionName,
            final JavadocLinks links,
            final File javadocDir,
            final RegionSupport regionSupport,
            final boolean useDependencies)
    throws MojoExecutionException {
        javadocDir.mkdirs();

        final Collection<ArtifactInfo> usedInfos = new ArrayList<>();

        final List<String> sourceDirectories = new ArrayList<>();
        final Set<String> javadocPackages = new HashSet<>();
        for(final ArtifactInfo info : ctx.getArtifactInfos(regionName, useDependencies)) {
            boolean addDirectory = false;
            for(final Clause clause : info.getUsedExportedPackages(regionName)) {
                if ( !ctx.getPackagesWithoutSources().contains(clause.getName()) && !links.getLinkedPackages().contains(clause.getName())) {
                    addDirectory = true;
                    javadocPackages.add(clause.getName());
                }
            }
            if ( addDirectory && info.getSourceDirectory() != null ) {
                usedInfos.add(info);
                sourceDirectories.add(info.getSourceDirectory().getAbsolutePath());
            }
        }

        if (javadocPackages.isEmpty()) {
            return Collections.emptyList();
        }

        // handle additional packages
        if ( !useDependencies) {
            for(final Artifact artifact : ApisUtil.getAdditionalJavadocArtifacts(ctx, regionName) ) {
                final boolean infoExists = ctx.getArtifactInfo(artifact.getId()) != null;
                final ArtifactInfo info = infoExists ?  ctx.getArtifactInfo(artifact.getId()) : ctx.addArtifactInfo(artifact);
    
                final Set<Clause> exportedPackages = regionSupport.getAllPublicPackages(ctx, artifact, getArtifactFile(artifact.getId()));
                final Iterator<Clause> iter = exportedPackages.iterator();
                final Set<String> exportedPackageNames = new LinkedHashSet<>();
                while ( iter.hasNext() ) {
                    final Clause c = iter.next();
                    if ( javadocPackages.contains(c.getName()) ) {
                        iter.remove();
                    } else {
                        javadocPackages.add(c.getName());
                        exportedPackageNames.add(c.getName());
                    }
                }
    
                if ( !exportedPackages.isEmpty() ) {
                    info.setUsedExportedPackages(regionName, exportedPackages, "");
                    if ( !infoExists ) {
                        info.setUsedExportedPackages(exportedPackageNames);
                        info.setSourceDirectory(new File(ctx.getDeflatedSourcesDir(), info.getId().toMvnName()));
                    }
    
                    usedInfos.add(info);
                    sourceDirectories.add(info.getSourceDirectory().getAbsolutePath());
                }
            }    
        } else {
            final Collection<ArtifactInfo> infos = ctx.getArtifactInfos(regionName, true);
            for(final ArtifactInfo i : ctx.getArtifactInfos(regionName, false)) {
                if ( !infos.contains(i) ) {
                    boolean addDirectory = false;
                    for(final Clause clause : i.getUsedExportedPackages(regionName)) {
                        if ( !ctx.getPackagesWithoutSources().contains(clause.getName()) && !links.getLinkedPackages().contains(clause.getName())) {
                            addDirectory = true;
                        }
                    }
                    if ( addDirectory && i.getSourceDirectory() != null ) {
                        sourceDirectories.add(i.getSourceDirectory().getAbsolutePath());
                    }        
                }
            }
        }

        final JavadocExecutor javadocExecutor = new JavadocExecutor(javadocDir.getParentFile())
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
                                          .addArgument(String.join(File.pathSeparator, sourceDirectories));

        javadocExecutor.addArgument("-source", false)
                       .addArgument(ctx.getConfig().getJavadocSourceLevel());

        final String versionSuffix = ctx.getConfig().getApiVersion() != null ? ctx.getConfig().getApiVersion() : ctx.getFeatureId().getVersion();

        if (!StringUtils.isBlank(project.getName())) {
            javadocExecutor.addArgument("-doctitle", false)
                           .addQuotedArgument(project.getName().trim().concat(" ").concat(versionSuffix));
        }

        if (!StringUtils.isBlank(project.getDescription())) {
            javadocExecutor.addArgument("-windowtitle", false)
                           .addQuotedArgument(project.getDescription().trim().concat(" ").concat(versionSuffix));
        }

        if (!StringUtils.isBlank(project.getInceptionYear())
                && project.getOrganization() != null
                && !StringUtils.isBlank(project.getOrganization().getName())) {
            javadocExecutor.addArgument("-bottom", false)
                           .addQuotedArgument(String.format("Copyright &copy; %s - %s %s. All Rights Reserved",
                                              project.getInceptionYear().trim(),
                                              Calendar.getInstance().get(Calendar.YEAR),
                                              project.getOrganization().getName().trim()));
        }

        if ( !links.getJavadocLinks().isEmpty()) {
            javadocExecutor.addArguments("-link", links.getJavadocLinks());
        }

        // classpath
        final Collection<String> classpath = ApisUtil.getJavadocClassPath(getLog(), repositorySystem, mavenSession,
                ctx, regionName);
        if (!classpath.isEmpty()) {
            javadocExecutor.addArgument("-classpath", false)
                           .addArgument(classpath, File.pathSeparator);
        }

        // turn off doclint
        javadocExecutor.addArgument("-Xdoclint:none");

        javadocExecutor.addArgument("--allow-script-in-comments");

        if ( !this.javadocIndex ) {
            javadocExecutor.addArgument("-noindex");
        }
        if ( !this.javadocTree ) {
            javadocExecutor.addArgument("-notree");
        }

        // list packages
        javadocExecutor.addArguments(javadocPackages);

        javadocExecutor.execute(javadocDir, getLog(), this.ignoreJavadocErrors);

        return usedInfos;
    }

    private File createLicenseReport(final ApisJarContext ctx,
            final ApiRegion region,
            final Collection<ArtifactInfo> infos,
            final List<String> report) throws MojoExecutionException {
        final File out = new File(this.getTmpDir(), region.getName() + "-license-report.txt");
        if ( !out.exists() ) {

            final List<String> output = new ArrayList<>();

            output.add(ctx.getConfig().getLicenseReportHeader());
            output.add("");
            for(final ArtifactInfo info : infos) {
                final String licenseDefault = ctx.getConfig().getLicenseDefault(info.getId());

                final StringBuilder sb = new StringBuilder(info.getId().toMvnId());
                boolean exclude = false;
                if ( licenseDefault != null ) {
                    if ( licenseDefault.isEmpty()) {
                        exclude = true;
                        getLog().debug("Excluding from license report " + info.getId().toMvnId());
                    } else {
                        sb.append(" - License(s) : ");
                        sb.append(licenseDefault);
                    }
                } else {
                    final List<License> licenses = this.getLicenses(ctx, info);

                    if ( !licenses.isEmpty() ) {
                        sb.append(" - License(s) : ");
                        sb.append(String.join(", ",
                                licenses.stream()
                                        .map(l -> l.getName().concat(" (").concat(l.getUrl()).concat(")"))
                                        .collect(Collectors.toList())));
                    } else {
                        report.add("No license info found for ".concat(info.getId().toMvnId()));
                    }
                }
                if ( !exclude ) {
                    output.add(sb.toString());
                }
            }
            if ( ctx.getConfig().getLicenseReportFooter() != null ) {
                output.add("");
                output.add(ctx.getConfig().getLicenseReportFooter());
            }
            try {
                Files.write(out.toPath(), output);
            } catch (IOException e) {
                throw new MojoExecutionException("Unable to write license report: " + e.getMessage(), e);
            }
        }
        return out;
    }

    private List<License> getLicenses(final ApisJarContext ctx, final ArtifactInfo info) {
        getLog().debug("Getting license for " + info.getId().toMvnId());
        List<License> result = info.getLicenses();
        if  ( result == null ) {
            try {
                ArtifactId id = info.getId();
                do {
                    final Model model = getArtifactPom(ctx, id);
                    final List<License> ll = model.getLicenses();

                    if ( ll != null && !ll.isEmpty() ) {
                        getLog().debug("Found license for " + id.toMvnId());
                        result = ll;
                    } else {
                        if ( model.getParent() != null ) {
                            final ArtifactId newId = new ArtifactId(model.getParent().getGroupId(), model.getParent().getArtifactId(), model.getParent().getVersion(), null, "pom");
                            if ( newId.equals(id) ) {
                                break;
                            } else {
                                id = newId;
                            }
                        } else {
                            break;
                        }
                    }
                }  while (result == null);
            } catch (MojoExecutionException m) {
                // nothing to do
            }
            if ( result == null ) {
                result = Collections.emptyList();
            }
            info.setLicenses(result);
        }
        getLog().debug("License for " + info.getId().toMvnId() + " = " + result);
        return result;
    }

    private List<ArtifactInfo> getAdditionalJavadocArtifacts(final ApisJarContext ctx, final ApiRegion region, final RegionSupport regionSupport)
    throws MojoExecutionException {
        final List<ArtifactInfo> result = new ArrayList<>();
        for(final Artifact artifact : ApisUtil.getAdditionalJavadocArtifacts(ctx, region.getName()) ) {
            final ArtifactInfo info = new ArtifactInfo(artifact);

            final Set<Clause> exportedPackages = regionSupport.getAllPublicPackages(ctx, artifact, getArtifactFile(artifact.getId()));
            final Set<String> exportedPackageNames = new LinkedHashSet<>();
            final Iterator<Clause> clauseIter = exportedPackages.iterator();
            while ( clauseIter.hasNext() ) {
                final Clause c = clauseIter.next();
                if ( region.getAllExportByName(c.getName()) == null ) {
                    exportedPackageNames.add(c.getName());
                } else {
                    clauseIter.remove();
                }
            }

            if ( !exportedPackages.isEmpty() ) {
                info.setUsedExportedPackages(region.getName(), exportedPackages, "");
                info.setUsedExportedPackages(exportedPackageNames);
                info.setSourceDirectory(new File(ctx.getDeflatedSourcesDir(), info.getId().toMvnName()));
                final boolean skipSourceDeflate = info.getSourceDirectory().exists();
                if (skipSourceDeflate) {
                    getLog().debug("Source for artifact " + info.getId().toMvnName() + " already deflated");
                    info.addSourceInfo("USE CACHE FROM PREVIOUS BUILD");
                } else {
                    this.downloadSources(ctx, info, artifact);
                }
                result.add(info);
            }
        }
        return result;
    }
}

