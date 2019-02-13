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
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Formatter;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Manifest;

import javax.json.Json;
import javax.json.stream.JsonParser;
import javax.json.stream.JsonParser.Event;

import org.apache.commons.lang3.JavaVersion;
import org.apache.commons.lang3.SystemUtils;
import org.apache.felix.utils.manifest.Clause;
import org.apache.felix.utils.manifest.Parser;
import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
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
import org.apache.sling.feature.maven.ProjectHelper;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.osgi.framework.Constants;

import edu.emory.mathcs.backport.java.util.Arrays;

/**
 * Generates the APIs JARs for each selected Feature file.
 */
@Mojo(name = "apis-jar",
    defaultPhase = LifecyclePhase.PACKAGE,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true
)
public class ApisJarMojo extends AbstractIncludingFeatureMojo {

    private static final String API_REGIONS_KEY = "api-regions";

    private static final String SCM_TAG = "scm-tag";

    private static final String SCM_LOCATION = "scm-location";

    private static final String NAME_KEY = "name";

    private static final String EXPORTS_KEY = "exports";

    private static final String APIS = "apis";

    private static final String SOURCES = "sources";

    private static final String JAVADOC = "javadoc";

    private static final String JAVA_EXTENSION = ".java";

    private static final String NON_ASCII_PATTERN = "[^\\p{ASCII}]";

    private static final String EMPTY_STRING = " ";

    @Parameter
    private FeatureSelectionConfig selection;

    @Parameter(defaultValue = "${project.build.directory}/apis-jars", readonly = true)
    private File mainOutputDir;

    @Parameter
    private String[] includeResources;

    @Parameter
    private Set<String> excludeRegions;

    @Parameter
    private String[] javadocLinks;

    @Component(hint = "default")
    private ModelBuilder modelBuilder;

    @Component
    private ScmManager scmManager;

    @Component
    private ArchiverManager archiverManager;

    private ArtifactProvider artifactProvider;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        ProjectHelper.checkPreprocessorRun(this.project);

        artifactProvider = new ArtifactProvider() {

            @Override
            public File provide(final ArtifactId id) {
                return ProjectHelper.getOrResolveArtifact(project, mavenSession, artifactHandlerManager, artifactResolver, id).getFile();
            }

        };

        getLog().debug("Retrieving Feature files...");
        final Collection<Feature> features = this.getSelectedFeatures(selection).values();

        if (features.isEmpty()) {
            getLog().debug("There are no assciated Feature files to current project, plugin execution will be interrupted");
            return;
        }

        getLog().debug("Starting APIs JARs creation...");

        for (final Feature feature : features) {
            onFeature(feature);
        }
    }

    private void onFeature(Feature feature) throws MojoExecutionException {
        getLog().debug(MessageUtils.buffer().a("Creating APIs JARs for Feature ").strong(feature.getId().toMvnId())
                .a(" ...").toString());

        Extensions extensions = feature.getExtensions();
        Extension apiRegionsExtension = extensions.getByName(API_REGIONS_KEY);
        if (apiRegionsExtension == null) {
            getLog().debug("Feature file " + feature.getId().toMvnId() + " does not declare '" + API_REGIONS_KEY + "' extension, no API JAR will be created");
            return;
        }

        String jsonRepresentation = apiRegionsExtension.getJSON();
        if (jsonRepresentation == null || jsonRepresentation.isEmpty()) {
            getLog().debug("Feature file " + feature.getId().toMvnId() + " declares an empty '" + API_REGIONS_KEY + "' extension, no API JAR will be created");
            return;
        }

        if (!mainOutputDir.exists()) {
            mainOutputDir.mkdirs();
        }

        // first output level is the aggregated feature
        File featureDir = new File(mainOutputDir, feature.getId().getArtifactId());

        File deflatedBinDir = newDir(featureDir, "deflated-bin");
        File deflatedSourcesDir = newDir(featureDir, "deflated-sources");
        File checkedOutSourcesDir = newDir(featureDir, "checkouts");

        // calculate all api-regions first, taking the inheritance in account
        List<ApiRegion> apiRegions = fromJson(feature, jsonRepresentation);

        // for each artifact included in the feature file:
        for (Artifact artifact : feature.getBundles()) {
            ArtifactId artifactId = artifact.getId();
            File bundle = retrieve(artifactId);
            // deflate all bundles first, in order to copy APIs and resources later, depending to the region
            File deflatedBundleDirectory = deflate(deflatedBinDir, bundle);

            // renaming potential name-collapsing resources
            renameResources(deflatedBundleDirectory, artifactId);

            // calculate the exported versioned packages in the manifest file for each region
            computeExportPackage(apiRegions, deflatedBundleDirectory, artifactId);

            // download sources
            downloadSources(artifact, deflatedSourcesDir, checkedOutSourcesDir);
        }

        // recollect and package stuff
        for (ApiRegion apiRegion : apiRegions) {
            if (excludeRegions != null
                    && !excludeRegions.isEmpty()
                    && excludeRegions.contains(apiRegion.getName())) {
                getLog().debug("API Region " + apiRegion.getName() + " will not processed since it is in the exclude list");
                continue;
            }

            File regionDir = new File(featureDir, apiRegion.getName());

            File apisDir = new File(regionDir, APIS);
            recollect(featureDir, deflatedBinDir, apiRegion, apisDir);
            inflate(feature.getId(), apisDir, apiRegion, APIS);

            File sourcesDir = new File(regionDir, SOURCES);
            recollect(featureDir, deflatedSourcesDir, apiRegion, sourcesDir);
            inflate(feature.getId(), sourcesDir, apiRegion, SOURCES);

            File javadocsDir = new File(regionDir, JAVADOC);
            generateJavadoc(apiRegion, sourcesDir, javadocsDir);
            inflate(feature.getId(), javadocsDir, apiRegion, JAVADOC);
        }

        getLog().debug(MessageUtils.buffer().a("APIs JARs for Feature ").debug(feature.getId().toMvnId())
                .a(" succesfully created").toString());
    }

    private File retrieve(ArtifactId artifactId) {
        getLog().debug("Retrieving artifact " + artifactId + "...");
        File sourceFile = artifactProvider.provide(artifactId);
        getLog().debug("Artifact " + artifactId + " successfully retrieved");
        return sourceFile;
    }

    private File deflate(File deflatedDir, File artifact) throws MojoExecutionException {
        getLog().debug("Deflating bundle " + artifact + "...");

        File destDirectory = new File(deflatedDir, artifact.getName());
        destDirectory.mkdirs();

        // unarchive the bundle first

        try {
            UnArchiver unArchiver = archiverManager.getUnArchiver(artifact);
            unArchiver.setSourceFile(artifact);
            unArchiver.setDestDirectory(destDirectory);
            unArchiver.extract();
        } catch (NoSuchArchiverException e) {
            throw new MojoExecutionException("An error occurred while deflating file "
                                             + artifact
                                             + " to directory "
                                             + destDirectory, e);
        }

        getLog().debug("Bundle " + artifact + " successfully deflated");

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
            File resource = new File(destDirectory, resourceName);
            File renamed = new File(resource.getParentFile(), artifactId.getGroupId() + "-" + artifactId.getArtifactId() + "-" + resource.getName());

            getLog().debug("Renaming resource " + resource + " to " + renamed + "...");

            if (resource.renameTo(renamed)) {
                getLog().debug("Resource renamed to " + renamed);
            } else {
                getLog().warn("Impossible to rename resource " + resource + " to " + renamed + ", please check the current user has enough rights on the File System");
            }
        }

        getLog().debug(Arrays.toString(includeResources) + " resources in " + destDirectory + " successfully renamed");
    }

    private void downloadSources(Artifact artifact, File deflatedSourcesDir, File checkedOutSourcesDir) throws MojoExecutionException {
        ArtifactId artifactId = artifact.getId();
        ArtifactId sourcesArtifactId = newArtifacId(artifactId,
                                                    "sources",
                                                    "jar");
        try {
            File sourcesBundle = retrieve(sourcesArtifactId);
            deflate(deflatedSourcesDir, sourcesBundle);
        } catch (Throwable t) {
            getLog().warn("Impossible to download -sources bundle "
                          + sourcesArtifactId
                          + " due to "
                          + t.getMessage()
                          + ", following back to source checkout...");

            // fallback to Artifacts SCM metadata first
            String connection = artifact.getMetadata().get(SCM_LOCATION);
            String tag = artifact.getMetadata().get(SCM_TAG);

            // Artifacts SCM metadata may not available or are an override, let's fallback to the POM
            ArtifactId pomArtifactId = newArtifacId(artifactId, null, "pom");
            getLog().debug("Falling back to SCM checkout, retrieving POM " + pomArtifactId + "...");
            // POM file must exist, let the plugin fail otherwise
            File pomFile = retrieve(pomArtifactId);
            getLog().debug("POM " + pomArtifactId + " successfully retrieved, reading the model...");

            // read model
            Model pomModel = modelBuilder.buildRawModel(pomFile, ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL, false).get();
            getLog().debug("POM model " + pomArtifactId + " successfully read, processing the SCM...");

            Scm scm = pomModel.getScm();
            if (scm != null) {
                connection = setIfNull(connection, scm.getConnection());
                tag = setIfNull(tag, scm.getTag());
            }

            if (connection == null) {
                getLog().warn("SCM not defined in "
                              + artifactId
                              + " bundle neither in "
                              + pomArtifactId
                              + " POM file, sources can not be retrieved, then will be ignored");
                return;
            }

            try {
                ScmRepository repository = scmManager.makeScmRepository(connection);

                ScmVersion scmVersion = null;
                if (tag != null) {
                    scmVersion = new ScmTag(tag);
                }

                File basedir = newDir(checkedOutSourcesDir, artifactId.getArtifactId());
                ScmFileSet fileSet = new ScmFileSet(basedir);

                CheckOutScmResult result = null;
                try {
                    if (scmVersion != null) {
                        result = scmManager.checkOut(repository, fileSet, true);
                    } else {
                        result = scmManager.checkOut(repository, fileSet, scmVersion, true);
                    }
                } catch (ScmException se) {
                    throw new MojoExecutionException("An error occurred while checking sources from "
                                                     + repository
                                                     + " for artifact "
                                                     + artifactId
                                                     + " model", se);
                }

                if (!result.isSuccess()) {
                    throw new MojoExecutionException("An error occurred while checking out sources from "
                                                     + connection
                                                     + ": "
                                                     + result.getProviderMessage());
                }

                // retrieve the exact pom location to detect the bundle path in the repo
                DirectoryScanner pomScanner = new DirectoryScanner();
                pomScanner.setBasedir(basedir);
                pomScanner.setIncludes("**/pom.xml");
                pomScanner.scan();
                for (String pomFileLocation : pomScanner.getIncludedFiles()) {
                    pomFile = new File(basedir, pomFileLocation);
                    pomModel = modelBuilder.buildRawModel(pomFile, ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL, false).get();

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
                        getLog().debug(artifactId + " does not contain any source, will be ignored");
                        return;
                    }
                }

                File destDirectory = newDir(deflatedSourcesDir, artifactId.toMvnId());

                DirectoryScanner directoryScanner = new DirectoryScanner();
                directoryScanner.setBasedir(javaSources);
                directoryScanner.setIncludes("*");
                directoryScanner.scan();

                for (String file : directoryScanner.getIncludedFiles()) {
                    File source = new File(javaSources, file);
                    File destination = new File(destDirectory, file);
                    destination.getParentFile().mkdirs();
                    try {
                        FileUtils.copyFile(source, destination);
                    } catch (IOException e) {
                        throw new MojoExecutionException("An error occurred while copying sources from " + source + " to " + destination, e);
                    }
                }
            } catch (ScmRepositoryException se) {
                throw new MojoExecutionException("An error occurred while reading SCM from "
                                                 + connection
                                                 + " connection for bundle "
                                                 + artifactId, se);
            } catch (NoSuchScmProviderException nsspe) {
                getLog().error(artifactId
                               + " bundle points to an SCM connection "
                               + connection
                               + " which does not specify a valid or supported SCM provider", nsspe);
            }
        }
    }

    private void computeExportPackage(List<ApiRegion> apiRegions, File destDirectory, ArtifactId artifactId) throws MojoExecutionException {
        File manifestFile = new File(destDirectory, "META-INF/MANIFEST.MF");

        getLog().debug("Reading Manifest headers from file " + manifestFile);

        if (!manifestFile.exists()) {
            throw new MojoExecutionException("Manifest file "
                    + manifestFile
                    + " does not exist, make sure "
                    + destDirectory
                    + " contains the valid deflated "
                    + artifactId
                    + " bundle");
        }

        try (FileInputStream input = new FileInputStream(manifestFile)) {
            Manifest manifest = new Manifest(input);
            String exportPackageHeader = manifest.getMainAttributes().getValue(Constants.EXPORT_PACKAGE);
            Clause[] exportPackages = Parser.parseHeader(exportPackageHeader);

            // filter for each region
            for (Clause exportPackage : exportPackages) {
                String api = exportPackage.getName();

                for (ApiRegion apiRegion : apiRegions) {
                    if (apiRegion.containsApi(api)) {
                        apiRegion.addExportPackage(exportPackage);
                    }
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("An error occurred while reading " + manifestFile + " file", e);
        }
    }

    private void recollect(File featureDir, File deflatedDir, ApiRegion apiRegion, File destination) throws MojoExecutionException {
        destination.mkdirs();

        DirectoryScanner directoryScanner = new DirectoryScanner();
        directoryScanner.setBasedir(deflatedDir);

        // for each region, include both APIs and resources
        String[] includes;
        if (APIS.equals(destination.getName())) {
            includes = concatenate(apiRegion.getFilteringApis(), includeResources);
        } else {
            includes = apiRegion.getFilteringApis();
        }
        directoryScanner.setIncludes(includes);
        directoryScanner.scan();

        for (String includedFile : directoryScanner.getIncludedFiles()) {
            File target = new File(destination, includedFile.substring(includedFile.indexOf(File.separator) + 1));
            target.getParentFile().mkdirs();

            try {
                File source = new File(deflatedDir, includedFile);

                // this to prevent 'unmappable character for encoding UTF8' error
                if (includedFile.endsWith(JAVA_EXTENSION)) {
                    String javaSource = FileUtils.fileRead(source, StandardCharsets.UTF_8.name())
                                                 .replaceAll(NON_ASCII_PATTERN, EMPTY_STRING);
                    FileUtils.fileWrite(target, StandardCharsets.UTF_8.name(), javaSource);
                } else {
                    FileUtils.copyFile(source, target);
                }
            } catch (IOException e) {
                throw new MojoExecutionException("An error occurred while copying file "
                                                 + includedFile
                                                 + " to "
                                                 + destination, e);
            }
        }
    }

    private void inflate(ArtifactId featureId, File collectedDir, ApiRegion apiRegion, String classifier) throws MojoExecutionException {
        DirectoryScanner directoryScanner = new DirectoryScanner();
        directoryScanner.setBasedir(collectedDir);
        directoryScanner.setIncludes("**/*.*");
        directoryScanner.scan();

        JarArchiver jarArchiver = new JarArchiver();
        for (String includedFile : directoryScanner.getIncludedFiles()) {
            jarArchiver.addFile(new File(collectedDir, includedFile), includedFile);
        }

        String bundleName;
        if (featureId.getClassifier() != null) {
            bundleName = String.format("%s-%s-%s-%s",
                    featureId.getArtifactId(),
                    featureId.getClassifier(),
                    apiRegion.getName(),
                    classifier);
        } else {
            bundleName = String.format("%s-%s-%s",
                    featureId.getArtifactId(),
                    apiRegion.getName(),
                    classifier);
        }
        String symbolicName = bundleName.replace('-', '.');

        MavenArchiveConfiguration archiveConfiguration = new MavenArchiveConfiguration();
        if (APIS.equals(classifier)) {
            // APIs need OSGi Manifest entry
            archiveConfiguration.addManifestEntry("Export-Package", StringUtils.join(apiRegion.getExportPackage(), ","));
            archiveConfiguration.addManifestEntry("Bundle-Description", project.getDescription());
            archiveConfiguration.addManifestEntry("Bundle-Version", featureId.getOSGiVersion().toString());
            archiveConfiguration.addManifestEntry("Bundle-ManifestVersion", "2");
            archiveConfiguration.addManifestEntry("Bundle-SymbolicName", symbolicName);
            archiveConfiguration.addManifestEntry("Bundle-Name", bundleName);
        }
        if (project.getOrganization() != null) {
            archiveConfiguration.addManifestEntry("Bundle-Vendor", project.getOrganization().getName());
        }
        archiveConfiguration.addManifestEntry("Specification-Version", featureId.getVersion());
        archiveConfiguration.addManifestEntry("Implementation-Title", bundleName);

        String targetName = String.format("%s-%s.jar", bundleName, featureId.getVersion());
        File target = new File(mainOutputDir, targetName);
        MavenArchiver archiver = new MavenArchiver();
        archiver.setArchiver(jarArchiver);
        archiver.setOutputFile(target);

        try {
            archiver.createArchive(mavenSession, project, archiveConfiguration);
            projectHelper.attachArtifact(project, "jar", featureId.getClassifier() + APIS, target);
        } catch (Exception e) {
            throw new MojoExecutionException("An error occurred while creating APIs "
                    + target
                    +" archive", e);
        }
    }

    private void generateJavadoc(ApiRegion apiRegion, File sourcesDir, File javadocDir) throws MojoExecutionException {
        javadocDir.mkdirs();

        JavadocExecutor javadocExecutor = new JavadocExecutor()
                                          .addArgument("-public")
                                          .addArgument("-d")
                                          .addArgument(javadocDir.getAbsolutePath())
                                          .addArgument("-sourcepath")
                                          .addArgument(sourcesDir.getAbsolutePath());

        if (isNotEmpty(project.getName())) {
            javadocExecutor.addArgument("-doctitle")
                           .addQuotedArgument(project.getName());
        }

        if (isNotEmpty(project.getDescription())) {
            javadocExecutor.addArgument("-windowtitle")
                           .addQuotedArgument(project.getDescription());
        }

        if (isNotEmpty(project.getInceptionYear())
                && project.getOrganization() != null
                && isNotEmpty(project.getOrganization().getName())) {
            javadocExecutor.addArgument("-bottom")
                           .addQuotedArgument(String.format("Copyright &copy; %s - %s %s. All Rights Reserved",
                                              project.getInceptionYear(),
                                              Calendar.getInstance().get(Calendar.YEAR),
                                              project.getOrganization().getName()));
        }

        if (javadocLinks != null && javadocLinks.length > 0) {
            javadocExecutor.addArguments("-link", javadocLinks);
        }

        //javadocExecutor.addArgument("-classpath")
        //               .addArgument(javadocClasspath, File.pathSeparator);

        // turn off doclint when running Java8
        // http://blog.joda.org/2014/02/turning-off-doclint-in-jdk-8-javadoc.html
        if (SystemUtils.isJavaVersionAtLeast(JavaVersion.JAVA_1_8)) {
            javadocExecutor.addArgument("-Xdoclint:none").addArgument("-quiet");
        }

        javadocExecutor.addArguments(apiRegion.apis).execute(javadocDir, getLog());
    }

    private static ArtifactId newArtifacId(ArtifactId original, String classifier, String type) {
        return new ArtifactId(original.getGroupId(),
                              original.getArtifactId(),
                              original.getVersion(),
                              classifier,
                              type);
    }

    private static List<ApiRegion> fromJson(Feature feature, String jsonRepresentation) throws MojoExecutionException {
        List<ApiRegion> apiRegions = new ArrayList<>();

        // pointers
        Event event;
        ApiRegion apiRegion;

        JsonParser parser = Json.createParser(new StringReader(jsonRepresentation));
        if (Event.START_ARRAY != parser.next()) {
            throw new MojoExecutionException("Expected 'api-region' element to start with an Array in Feature "
                                             + feature.getId().toMvnId()
                                             + ": "
                                             + parser.getLocation());
        }

        while (Event.END_ARRAY != (event = parser.next())) {
            if (Event.START_OBJECT != event) {
                throw new MojoExecutionException("Expected 'api-region' data to start with an Object in Feature "
                                                 + feature.getId().toMvnId()
                                                 + ": "
                                                 + parser.getLocation());
            }

            apiRegion = new ApiRegion();

            while (Event.END_OBJECT != (event = parser.next())) {
                if (Event.KEY_NAME == event) {
                    switch (parser.getString()) {
                        case NAME_KEY:
                            parser.next();
                            apiRegion.setName(parser.getString());
                            break;

                        case EXPORTS_KEY:
                            // start array
                            parser.next();

                            while (parser.hasNext() && Event.VALUE_STRING == parser.next()) {
                                String api = parser.getString();
                                // skip comments
                                if ('#' != api.charAt(0)) {
                                    apiRegion.addApi(api);
                                }
                            }

                            break;

                        default:
                            break;
                    }
                }
            }

            // inherit all APIs from previous region, if any
            if (apiRegions.size() > 0) {
                apiRegion.doInherit(apiRegions.get(apiRegions.size() - 1));
            }

            apiRegions.add(apiRegion);
        }

        return apiRegions;
    }

    private static final class ApiRegion {

        private final Set<String> apis = new TreeSet<>();

        private final Set<String> filteringApis = new TreeSet<>();

        private final List<Clause> exportPackage = new ArrayList<>();

        private String name;

        private String inherits;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String[] getFilteringApis() {
            return filteringApis.toArray(new String[filteringApis.size()]);
        }

        public void addApi(String api) {
            apis.add(api);
            filteringApis.add("**/" + api.replace('.', '/') + "/*");
        }

        public boolean containsApi(String api) {
            return apis.contains(api);
        }

        public void addExportPackage(Clause exportPackage) {
            this.exportPackage.add(exportPackage);
        }

        public Iterator<Clause> getExportPackage() {
            return exportPackage.iterator();
        }

        public void doInherit(ApiRegion parent) {
            inherits = parent.getName();
            apis.addAll(parent.apis);
            filteringApis.addAll(parent.filteringApis);
        }

        @Override
        public String toString() {
            Formatter formatter = new Formatter();
            formatter.format("Region '%s'", name);

            if (inherits != null && !inherits.isEmpty()) {
                formatter.format(" inherits from '%s'", inherits);
            }

            formatter.format(":%n");

            for (String api : apis) {
                formatter.format(" * %s%n", api);
            }

            String toString = formatter.toString();
            formatter.close();

            return toString;
        }

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

}
