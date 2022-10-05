/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.feature.maven.mojos;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.repository.legacy.metadata.ArtifactMetadataSource;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Artifacts;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.feature.maven.JSONFeatures;
import org.apache.sling.feature.maven.ProjectHelper;
import org.codehaus.plexus.util.StringUtils;

/**
 * Update the bundles/artifact versions
 */
@Mojo(
        name = "update-feature-versions",
        threadSafe = true
    )
public class UpdateVersionsMojo extends AbstractIncludingFeatureMojo {

    private static final int INFO_PAD_SIZE = 95;

    /**
     * A comma separated list of artifact patterns to include. Follows the pattern
     * "groupId:artifactId:type:classifier:version". Designed to allow specifying
     * the set of includes from the command line.
     */
    @Parameter(property = "includes")
    private String updatesIncludesList;

    /**
     * A comma separated list of artifact patterns to exclude. Follows the pattern
     * "groupId:artifactId:type:classifier:version". Designed to allow specifying
     * the set of excludes from the command line.
     */
    @Parameter(property = "excludes")
    private String updatesExcludesList;

    /**
     * The scope to use to find the highest version, use ANY, MAJOR, MINOR,
     * INCREMENTAL, or SUBINCREMENTAL
     */
    @Parameter(property = "versionScope")
    private String versionScope;

    /**
     * If set to true, no changes are performed
     */
    @Parameter(defaultValue = "false", property = "dryRun")
    private boolean dryRun;

    /**
     * A comma separated list of classifiers to select the feature files.
     * Use ':' to select the main artifact (no classifier).
     */
    @Parameter(property = "classifiers")
    private String classifiers;

    @Component(role = org.apache.maven.artifact.metadata.ArtifactMetadataSource.class)
    protected ArtifactMetadataSource artifactMetadataSource;

    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
    protected List<ArtifactRepository> remoteArtifactRepositories;

    private List<String[]> parseMatches(final String value, final String matchType) throws MojoExecutionException {
        List<String[]> matches = null;
        if (value != null) {
            matches = new ArrayList<>();
            for (final String t : value.split(",")) {
                final String[] val = t.split(":");
                if (val.length > 5) {
                    throw new MojoExecutionException("Illegal " + matchType + " " + val);
                }
                matches.add(val);
            }
        }
        return matches;
    }

    /**
     * Get the raw features (not the assembled ones)
     *
     * @return Map with the features
     * @throws MojoExecutionException
     */
    private Map<String, Feature> getFeatures() throws MojoExecutionException {
        final String[] selection = this.classifiers == null ? null : this.classifiers.split(",");
        final Map<String, Feature> features = new LinkedHashMap<>();
        for (final Map.Entry<String, Feature> entry : this.selectAllFeatureFiles().entrySet()) {
            boolean selected = true;
            if ( selection != null ) {
                selected = false;
                final String classifier = entry.getValue().getId().getClassifier();
                for(final String c : selection) {
                    if ( classifier == null ) {
                        if ( ":".equals(c) ) {
                            selected = true;
                            break;
                        }
                    } else if ( classifier.trim().equals(c)) {
                        selected = true;
                        break;
                    }
                }
            }
            if ( selected ) {
                features.put(entry.getKey(), ProjectHelper.getFeatures(project).get(entry.getKey()));
            }
        }
        if (features.isEmpty()) {
            throw new MojoExecutionException("No features found in project!");
        }
        return features;
    }

    /**
     * Get all dependencies to check for a list of artifacts
     * @param dependencies The result
     * @param artifacts The artifacts to check
     * @param cfg The configuration
     */
    private void addDependencies(final Set<ArtifactHolder> dependencies, 
            final List<Artifact> artifacts,
            final UpdateConfig cfg) {
        for (final Artifact a : artifacts) {
            final String include = shouldHandle(a.getId(), cfg);
            if (include != null) {
                String newVersion = null;
                Scope scope = cfg.defaultScope;
                if (!include.trim().isEmpty()) {
                    scope = getScope(include);
                    if (scope == null) {
                        newVersion = include;
                    }
                }
                dependencies.add(this.createArtifactHolder(a, scope, newVersion));
            }
        }
    }

    /**
     * Get all dependencies to check
     * @param features The map of features
     * @param cfg The configuration
     * @return The set of dependencies
     */
    private Set<ArtifactHolder> getDependencies(final Map<String, Feature> features, final UpdateConfig cfg) {
        final Set<ArtifactHolder> dependencies = new TreeSet<>();
        for (final Map.Entry<String, Feature> entry : features.entrySet()) {
            addDependencies(dependencies, entry.getValue().getBundles(), cfg);
            for (final Extension ext : entry.getValue().getExtensions()) {
                if (ext.getType() == ExtensionType.ARTIFACTS) {
                    addDependencies(dependencies, ext.getArtifacts(), cfg);
                }
            }
        }
        return dependencies;
    }

    private Feature readRawFeature(final String fileName) throws MojoExecutionException {
        // we need to read the raw file
        try (final Reader r = new FileReader(new File(fileName))) {
            final String json = JSONFeatures.read(r, JSONFeatures.PLACEHOLDER_ID, fileName);
            try ( final Reader featureReader = new StringReader(json)) {
                return FeatureJSONReader.read(featureReader, fileName);
            }
        } catch (final IOException e) {
            throw new MojoExecutionException("Unable to read feature file " + fileName, e);
        }
    }

    /**
     * Create the update configuration
     * @return The configuration
     * @throws MojoExecutionException If configuration is invalid
     */
    private UpdateConfig createConfiguration() throws MojoExecutionException {
        final UpdateConfig cfg = new UpdateConfig();

        // get includes and excludes
        cfg.includes = parseMatches(updatesIncludesList, "includes");
        // check for version info
        if (cfg.includes != null) {
            cfg.includeVersionInfo = new ArrayList<>();
            for (final String[] include : cfg.includes) {
                final int lastIndex = include.length - 1;
                final int pos = include[lastIndex].indexOf('/');
                if (pos != -1) {
                    cfg.includeVersionInfo.add(include[lastIndex].substring(pos + 1));
                    include[lastIndex] = include[lastIndex].substring(0, pos);
                } else {
                    cfg.includeVersionInfo.add("");
                }
            }
        }
        cfg.excludes = parseMatches(updatesExcludesList, "excludes");

        cfg.defaultScope = getScope(this.versionScope);
        if (cfg.defaultScope == null) {
            throw new MojoExecutionException("Invalid update scope specified: " + this.versionScope);
        }

        return cfg;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkPreconditions();

        // Get the raw features
        final Map<String, Feature> features = this.getFeatures();

        // Create config
        final UpdateConfig cfg = this.createConfiguration();

        // Calculate dependencies for features and get updates
        cfg.updateInfos = this.getDependencies(features, cfg);
        this.lookupVersionUpdates(cfg.updateInfos);

        final Map<String, UpdateResult> results = new LinkedHashMap<>();

        final Map<String, Set<String>> globalPropertyUpdates = new HashMap<String, Set<String>>();

        for (final Map.Entry<String, Feature> entry : features.entrySet()) {
            getLog().debug("Checking feature file " + entry.getKey());

            final UpdateResult result = new UpdateResult();
            results.put(entry.getKey(), result);

            result.updates = this.getUpdates(entry.getValue(), cfg);

            if (!result.updates.isEmpty()) {

                // read raw feature file
                final Feature rawFeature = this.readRawFeature(entry.getKey());

                if (updateVersions(entry.getKey(), rawFeature, result, globalPropertyUpdates) && !dryRun) {
                    try (final Writer w = new FileWriter(new File(entry.getKey()))) {
                        JSONFeatures.write(w, rawFeature);
                    } catch (final IOException e) {
                        throw new MojoExecutionException("Unable to write feature file " + entry.getValue(), e);
                    }
                }
    		}
    	}

        boolean printedHeader = false;
        for (final Map.Entry<String, UpdateResult> entry : results.entrySet()) {
            if (entry.getValue().updates.isEmpty()) {
                if (!printedHeader) {
                    getLog().info("");
                    getLog().info("The following features have no updates:");
                    printedHeader = true;
                }
                getLog().info("- " + entry.getKey());
            }
        }
        printedHeader = false;
        for (final Map.Entry<String, UpdateResult> entry : results.entrySet()) {
            if (!entry.getValue().updates.isEmpty()) {
                if (!printedHeader) {
                    getLog().info("");
                    if (this.dryRun) {
                        getLog().info("The following features could be updated:");
                    } else {
                        getLog().info("The following features are updated:");
                    }
                    printedHeader = true;
                }
                getLog().info("");
                getLog().info("Feature " + entry.getKey());

                for (final ArtifactUpdate update : entry.getValue().updates) {
                    final String left = "  " + update.artifact.getId().toMvnId() + "...";
                    final String right = " -> " + update.newVersion;

                    if (right.length() + left.length() > INFO_PAD_SIZE) {
                        getLog().info(left);
                        getLog().info(StringUtils.leftPad(right, INFO_PAD_SIZE));
                    } else {
                        getLog().info(StringUtils.rightPad(left, INFO_PAD_SIZE - right.length(), ".") + right);
                    }
                }

                if (!entry.getValue().propertyUpdates.isEmpty()) {
                    getLog().info("  The following properties in the pom should be updated:");
                    for (final Map.Entry<String, String> prop : entry.getValue().propertyUpdates.entrySet()) {
                        final String left = "    Property '" + prop.getKey() + "' to ...";
                        final String right = prop.getValue();
                        if (right.length() + left.length() > INFO_PAD_SIZE) {
                            getLog().info(left);
                            getLog().info(StringUtils.leftPad(right, INFO_PAD_SIZE));
                        } else {
                            getLog().info(StringUtils.rightPad(left, INFO_PAD_SIZE - right.length(), ".") + right);
                        }
                    }
                }
            }
        }

        if (!globalPropertyUpdates.isEmpty()) {
            getLog().info("Update summary for properties in the pom:");
            for (final Map.Entry<String, Set<String>> entry : globalPropertyUpdates.entrySet()) {
                if (entry.getValue().size() > 1) {
                    throw new MojoExecutionException("Inconsistent use of version property " + entry.getKey()
                            + ". Different version updates available: " + entry.getValue());
                }
                final String value = entry.getValue().iterator().next();
                final Object oldValue = this.project.getProperties().get(entry.getKey());
                if (oldValue == null) {
                    throw new MojoExecutionException("Property '" + entry.getKey() + "' is not defined in POM");
                }
                getLog().info("  Please update property '" + entry.getKey() + "' from " + oldValue + " to " + value);
            }
        }
    }

    private String shouldHandle(final ArtifactId id, final UpdateConfig cfg) {
        String include = "";
        if (cfg.includes != null) {
            include = match(id, cfg.includes, cfg.includeVersionInfo);
        }
        if (include != null && cfg.excludes != null) {
            if (match(id, cfg.excludes, null) != null) {
                include = null;
            }
        }
        return include;
    }

    private boolean match(final String value, final String matcher) {
        if (matcher.endsWith("*")) {
            return value.startsWith(matcher.substring(0, matcher.length() - 1));
        }
        return matcher.equals(value);
    }

    private String match(final ArtifactId id, final List<String[]> matches, final List<String> versionInfo) {
        boolean match = false;

        int index = 0;
        for(final String[] m : matches) {
            match = match(id.getGroupId(), m[0]);
            if (match && m.length > 1) {
                match = match(id.getArtifactId(), m[1]);
            }
            if (match && m.length == 3) {
                match = match(id.getVersion(), m[2]);
            } else if (match && m.length == 4) {
                match = match(id.getVersion(), m[3]);
                if (match) {
                    match = match(id.getType(), m[2]);
                }
            } else if (match && m.length == 5) {
                match = match(id.getVersion(), m[4]);
                if (match) {
                    match = match(id.getType(), m[2]);
                    if (match) {
                        match = match(id.getClassifier(), m[3]);
                    }
                }
            }
            if (match) {
                break;
            }
            index++;
        }
        if (match) {
            if (versionInfo != null) {
                return versionInfo.get(index);
            }
            return "";
        }
        return null;
    }

    private String update(final Artifact artifact, final Set<ArtifactHolder> updates)
            throws MojoExecutionException {
		getLog().debug("Searching for updates of " + artifact.getId().toMvnId());

        // check updates
        String found = null;
        for (final ArtifactHolder entry : updates) {
            if (artifact.getId().equals(entry.getArtifact().getId()) ) {
                found = entry.getUpdate();
                break;
            }
		}

		String updated = null;
		if ( found != null ) {
            getLog().debug("Updating " + artifact.getId().toMvnId() + " to " + found);

            updated = found;
		} else {
            getLog().debug("No newer version found for " + artifact.getId().toMvnId());
        }

		return updated;
	}

    public static final class UpdateConfig {
        public List<String[]> includes;
        public List<String[]> excludes;

        public List<String> includeVersionInfo;

        Set<ArtifactHolder> updateInfos;

        public Scope defaultScope;
	}

	public static final class ArtifactUpdate {
		public Extension extension;
		public Artifact artifact;
		public String newVersion;
	}

    public static final class UpdateResult {
        public List<ArtifactUpdate> updates;
        public Map<String, String> propertyUpdates = new HashMap<>();
    }

    private boolean updateVersions(final String fileName, final Feature rawFeature, final UpdateResult result,
            final Map<String, Set<String>> globalPropertyUpdates) throws MojoExecutionException {
        // update artifacts
        final Iterator<ArtifactUpdate> iter = result.updates.iterator();
        while (iter.hasNext()) {
            final ArtifactUpdate update = iter.next();

            final Artifacts container;
            if (update.extension == null) {
                container = rawFeature.getBundles();
            } else {
                container = rawFeature.getExtensions().getByName(update.extension.getName()).getArtifacts();
            }
            final int pos = container.indexOf(update.artifact);
            final Artifact oldArtifact = pos == -1 ? null : container.get(pos);
            if (!container.removeExact(update.artifact.getId())) {
                // check if property is used
                final Artifact same = container.getSame(update.artifact.getId());
                boolean found = same != null;
                if (same != null) {
                    if (!same.getId().getVersion().startsWith("${") || !same.getId().getVersion().endsWith("}")) {
                        found = false;
                    } else {
                        final String propName = same.getId().getVersion().substring(2,
                                same.getId().getVersion().length() - 1);
                        if (!update.artifact.getId().getVersion().equals(this.project.getProperties().get(propName))) {
                            found = false;
                        } else {
                            Set<String> versions = globalPropertyUpdates.get(propName);
                            if (versions == null) {
                                versions = new HashSet<>();
                                globalPropertyUpdates.put(propName, versions);
                            }
                            versions.add(update.newVersion);
                            result.propertyUpdates.put(propName, update.newVersion);
                        }
                    }
                }
                if (!found) {
                    throw new MojoExecutionException("Unable to update artifact as it's not found in feature: "
                            + update.artifact.getId().toMvnId());
                }
                iter.remove();
            } else {
                // oldArtifact is not null (removeExact returned true)
                final Artifact newArtifact = new Artifact(update.artifact.getId().changeVersion(update.newVersion));
                newArtifact.getMetadata().putAll(oldArtifact.getMetadata());
                container.add(pos, newArtifact);
            }
        }

        return !result.updates.isEmpty();
    }

    /**
     * Add the updates for a list of artifacts
     *
     * @param updates
     * @param ext
     * @param artifacts
     * @param cfg
     * @throws MojoExecutionException
     */
    private void addUpdates(final List<ArtifactUpdate> updates, final Extension ext, final Artifacts artifacts,
            final UpdateConfig cfg)
            throws MojoExecutionException {
        for (final Artifact a : artifacts) {
            if (shouldHandle(a.getId(), cfg) != null) {
                final String newVersion = update(a, cfg.updateInfos);
                if (newVersion != null) {
                    final ArtifactUpdate update = new ArtifactUpdate();
                    update.artifact = a;
                    update.newVersion = newVersion;
                    update.extension = ext;
                    updates.add(update);
                }
            }
        }
    }

    /**
     * Get all updates for a feature
     *
     * @param feature
     * @param cfg
     * @return
     * @throws MojoExecutionException
     */
    private List<ArtifactUpdate> getUpdates(final Feature feature, final UpdateConfig cfg)
            throws MojoExecutionException {
        final List<ArtifactUpdate> updates = new ArrayList<>();
        addUpdates(updates, null, feature.getBundles(), cfg);

        for (final Extension ext : feature.getExtensions()) {
            if (ext.getType() == ExtensionType.ARTIFACTS) {
                addUpdates(updates, ext, ext.getArtifacts(), cfg);
            }
        }

        return updates;
    }

    /**
     * Create a holder 
     * @param artifact The artifact
     * @param scope The scope
     * @param newVersion The optional new version
     * @return The holder
     */
    private ArtifactHolder createArtifactHolder(final Artifact artifact, 
            final Scope scope,
            final String newVersion) {
        return new ArtifactHolder(artifact, scope, newVersion);
    }

    /**
     * Lookup the version updates
     * @param dependencies The set of dependencies to check for version updates
     */
    private void lookupVersionUpdates( final Set<ArtifactHolder> dependencies )
        throws MojoExecutionException {

        final List<Callable<Void>> requestsForDetails = new ArrayList<>( dependencies.size() );
        for ( final ArtifactHolder dependency : dependencies ) {
            requestsForDetails.add( () -> {
                final ArtifactId id = dependency.getArtifact().getId();

                getLog().debug( "Checking " + id.getGroupId() + ":" + id.getArtifactId() + " for updates newer than " + id.getVersion() );

                final ArtifactHandler handler = artifactHandlerManager.getArtifactHandler( id.getType() );

                final org.apache.maven.artifact.Artifact artifact = new DefaultArtifact( id.getGroupId(), id.getArtifactId(), id.getVersion(), org.apache.maven.artifact.Artifact.SCOPE_PROVIDED, 
                                                            id.getType(), id.getClassifier(), handler);
                dependency.setVersions(artifactMetadataSource.retrieveAvailableVersions( artifact, 
                        this.mavenSession.getLocalRepository(), this.remoteArtifactRepositories ));
                return null;
            });
        }

        // Lookup details in parallel...
        final ExecutorService executor = Executors.newFixedThreadPool( 5 );
        try {
            final List<Future<Void>> responseForDetails = executor.invokeAll( requestsForDetails );

            // Construct the final results...
            for ( final Future<Void> details : responseForDetails ) {
                details.get();
            }
        } catch ( final ExecutionException | InterruptedException ie ) {
            throw new MojoExecutionException( "Unable to acquire metadata for dependencies " + dependencies
                + ": " + ie.getMessage(), ie );
        } finally {
            executor.shutdownNow();
        }
    }

    /** Enumeration for the update scopes */
    public enum Scope {
        ANY,
        MAJOR,
        MINOR,
        INCREMENTAL,
        SUBINCREMENTAL
    }

    /**
     * Get the update scope
     * @param versionInfo The version info
     * @return The scope or {@code null}
     */
    private static Scope getScope(final String versionInfo) {
        final Scope scope;
        if (versionInfo == null || "ANY".equalsIgnoreCase(versionInfo)) {
            scope = Scope.ANY;
        } else if ("MAJOR".equalsIgnoreCase(versionInfo)) {
            scope = Scope.MAJOR;
        } else if ("MINOR".equalsIgnoreCase(versionInfo)) {
            scope = Scope.MINOR;
        } else if ("INCREMENTAL".equalsIgnoreCase(versionInfo)) {
            scope = Scope.INCREMENTAL;
        } else if ("SUBINCREMENTAL".equalsIgnoreCase(versionInfo)) {
            scope = Scope.SUBINCREMENTAL;
        } else {
            scope = null;
        }
        return scope;
    }
    /**
     * Holds the results of a search for versions of an artifact.
     */
    private static class ArtifactHolder implements Comparable<ArtifactHolder> {

        /**
         * All versions - this is updated dynamically
         */
        private final SortedSet<ArtifactVersion> versions = new TreeSet<>();

        /**
         * The current version.
         */
        private final ArtifactVersion currentVersion;
 
        /**
         * The update scope to use
         */
        private final Scope updateScope;

        /**
         * The artifact
         */
        private final Artifact artifact;

        /**
         * Optional version to use for the update
         */
        private final String newVersion;

        /**
         * Constructor
         * @param artifact The artifact.
         * @param updateScope The update scope.
         * @param newVersion optional new version info
         */
        public ArtifactHolder( final Artifact artifact, final Scope updateScope, final String newVersion ) {
            this.artifact = artifact;
            this.updateScope = updateScope;
            this.currentVersion = new DefaultArtifactVersion(artifact.getId().getVersion());
            this.newVersion = newVersion;
        }

        /**
         * Get the artifact
         * @return The artifact
         */
        public Artifact getArtifact() {
            return this.artifact;
        }

        /**
         * Set the versions
         * @param versions List of versions
         */
        public void setVersions(final List<ArtifactVersion> versions) {
            // filter out snapshots
            for ( final ArtifactVersion candidate : versions ) {
                if ( ArtifactUtils.isSnapshot( candidate.toString() ) ) {
                    continue;
                }
                this.versions.add( candidate );
            }
        }

        private final ArtifactVersion getNewestVersion( ArtifactVersion lowerBound,
                                                        ArtifactVersion upperBound,
                                                        boolean includeLower, boolean includeUpper ) {
            ArtifactVersion latest = null;
            for ( final ArtifactVersion candidate : this.versions ) {
                final int lower = lowerBound == null ? -1 : lowerBound.compareTo( candidate );
                final int upper = upperBound == null ? +1 : upperBound.compareTo( candidate );
                if ( lower > 0 || upper < 0 ) {
                    continue;
                }
                if ( ( !includeLower && lower == 0 ) || ( !includeUpper && upper == 0 ) ) {
                    continue;
                }
                if ( ArtifactUtils.isSnapshot( candidate.toString() ) ) {
                    continue;
                }
                if ( latest == null ) {
                    latest = candidate;
                } else if ( latest.compareTo( candidate ) < 0 ) {
                    latest = candidate;
                }

            }
            return latest;
        }

        public final String getUpdate() {
            if ( this.newVersion != null ) {
                return this.newVersion;
            }
            ArtifactVersion v = null;
            switch ( updateScope ) {
                case SUBINCREMENTAL :
                    v = getSegmentCount( currentVersion ) < 3 ? null
                                : this.getNewestVersion( currentVersion,
                                                incrementSegment( currentVersion, 2 ),
                                                false, false );
                    break;
                case INCREMENTAL :
                    v = getSegmentCount( currentVersion ) < 3 ? null
                                : this.getNewestVersion( incrementSegment( currentVersion, 2 ),
                                                incrementSegment( currentVersion, 1 ),
                                                true, false );
                    break;
                case MINOR :
                    v = getSegmentCount( currentVersion ) < 2 ? null
                                : this.getNewestVersion( incrementSegment( currentVersion, 1 ),
                                                                incrementSegment( currentVersion, 0 ),
                                                                true, false );
                    break;
                case MAJOR :
                    v = getSegmentCount( currentVersion ) < 1 ? null
                                : this.getNewestVersion( incrementSegment( currentVersion, 0 ),
                                                                null, true, false );
                    break;
                case ANY :
                    v = this.getNewestVersion( currentVersion, null, false, false );
                    break;
            }
            return v != null ? v.toString() : null;
        }

        @Override
        public int compareTo(final ArtifactHolder o) {
            return this.artifact.compareTo(o.getArtifact());
        }
    }

    private static final Pattern SNAPSHOT_PATTERN = Pattern.compile( "(-((\\d{8}\\.\\d{6})-(\\d+))|(SNAPSHOT))$" );

    private static final int getSegmentCount( final ArtifactVersion v ) {
        if ( v == null ) {
            return 0;
        }
        if ( ArtifactUtils.isSnapshot( v.toString() ) ) {
            return innerGetSegmentCount( stripSnapshot( v ) );
        }
        return innerGetSegmentCount( v );
    }

    private static ArtifactVersion incrementSegment( final ArtifactVersion v, final int segment ) {
        if ( ArtifactUtils.isSnapshot( v.toString() ) ) {
            return copySnapshot( v, innerIncrementSegment( stripSnapshot( v ), segment ) );
        }
        return innerIncrementSegment( v, segment );
    }

    private static int innerGetSegmentCount( final ArtifactVersion v ) {
        // if the version does not match the maven rules, then we have only one segment
        // i.e. the qualifier
        if ( v.getBuildNumber() != 0 ) {
            // the version was successfully parsed, and we have a build number
            // have to have four segments
            return 4;
        }
        if ( ( v.getMajorVersion() != 0 || v.getMinorVersion() != 0 || v.getIncrementalVersion() != 0 )
            && v.getQualifier() != null ) {
            // the version was successfully parsed, and we have a qualifier
            // have to have four segments
            return 4;
        }
        final String version = v.toString();
        if ( version.indexOf( '-' ) != -1 ) {
            // the version has parts and was not parsed successfully
            // have to have one segment
            return version.equals( v.getQualifier() ) ? 1 : 4;
        }
        if ( version.indexOf( '.' ) != -1 ) {
            // the version has parts and was not parsed successfully
            // have to have one segment
            return version.equals( v.getQualifier() ) ? 1 : 3;
        }
        if ( StringUtils.isEmpty( version ) ) {
            return 3;
        }
        try {
            Integer.parseInt( version );
            return 3;
        } catch ( final NumberFormatException e ) {
            return 1;
        }
    }

    private static ArtifactVersion innerIncrementSegment( final ArtifactVersion v, final int segment ) {
        int segmentCount = innerGetSegmentCount( v );
        if ( segment < 0 || segment >= segmentCount ) {
            throw new IllegalArgumentException( v.toString() );
        }
        String version = v.toString();
        if ( segmentCount == 1 ) {
            // only the qualifier
            version = alphaNumIncrement( version );
            return new DefaultArtifactVersion( version );
        } else {
            int major = v.getMajorVersion();
            int minor = v.getMinorVersion();
            int incremental = v.getIncrementalVersion();
            int build = v.getBuildNumber();
            String qualifier = v.getQualifier();

            int minorIndex = version.indexOf( '.' );
            boolean haveMinor = minorIndex != -1;
            int incrementalIndex = haveMinor ? version.indexOf( '.', minorIndex + 1 ) : -1;
            boolean haveIncremental = incrementalIndex != -1;
            int buildIndex = version.indexOf( '-' );
            boolean haveBuild = buildIndex != -1 && qualifier == null;
            boolean haveQualifier = buildIndex != -1 && qualifier != null;

            switch ( segment ) {
                case 0:
                    major++;
                    minor = 0;
                    incremental = 0;
                    build = 0;
                    qualifier = null;
                    break;
                case 1:
                    minor++;
                    incremental = 0;
                    build = 0;
                    if ( haveQualifier && qualifier.endsWith( "SNAPSHOT" ) ) {
                        qualifier = "SNAPSHOT";
                    }
                    break;
                case 2:
                    incremental++;
                    build = 0;
                    qualifier = null;
                    break;
                case 3:
                    if ( haveQualifier ) {
                        qualifier = qualifierIncrement( qualifier );
                    } else {
                        build++;
                    }
                    break;
            }
            final StringBuilder result = new StringBuilder();
            result.append( major );
            if ( haveMinor || minor > 0 || incremental > 0 ) {
                result.append( '.' );
                result.append( minor );
            }
            if ( haveIncremental || incremental > 0 ) {
                result.append( '.' );
                result.append( incremental );
            }
            if ( haveQualifier && qualifier != null ) {
                result.append( '-' );
                result.append( qualifier );
            } else if ( haveBuild || build > 0 ) {
                result.append( '-' );
                result.append( build );
            }
            return new DefaultArtifactVersion( result.toString() );
        }
    }

    private static String qualifierIncrement( final String qualifier ) {
        if ( qualifier.toLowerCase().startsWith( "alpha" ) ) {
            return qualifier.substring( 0, 5 ) + alphaNumIncrement( qualifier.substring( 5 ) );
        }
        if ( qualifier.toLowerCase().startsWith( "beta" ) ) {
            return qualifier.substring( 0, 4 ) + alphaNumIncrement( qualifier.substring( 4 ) );
        }
        if ( qualifier.toLowerCase().startsWith( "milestone" ) ) {
            return qualifier.substring( 0, 8 ) + alphaNumIncrement( qualifier.substring( 8 ) );
        }
        if ( qualifier.toLowerCase().startsWith( "cr" ) || qualifier.toLowerCase().startsWith( "rc" ) || qualifier.toLowerCase().startsWith( "sp" ) ) {
            return qualifier.substring( 0, 2 ) + alphaNumIncrement( qualifier.substring( 2 ) );
        }
        return alphaNumIncrement( qualifier );
    }

    private static String alphaNumIncrement( String token ) {
        String newToken;
        int i = token.length();
        boolean done = false;
        newToken = token;
        while ( !done && i > 0 ) {
            i--;
            char c = token.charAt( i );
            if ( '0' <= c && c < '9' ) {
                c++;
                newToken = newToken.substring( 0, i ) + c + ( i + 1 < newToken.length() ? newToken.substring( i + 1 ) : "" );
                done = true;
            } else if ( c == '9' ) {
                c = '0';
                newToken = newToken.substring( 0, i ) + c + ( i + 1 < newToken.length() ? newToken.substring( i + 1 ) : "" );
            } else if ( 'A' <= c && c < 'Z' ) {
                c++;
                newToken = newToken.substring( 0, i ) + c + ( i + 1 < newToken.length() ? newToken.substring( i + 1 ) : "" );
                done = true;
            } else if ( c == 'Z' ) {
                c = 'A';
                newToken = newToken.substring( 0, i ) + c + ( i + 1 < newToken.length() ? newToken.substring( i + 1 ) : "" );
            } else if ( 'a' <= c && c < 'z' ) {
                c++;
                newToken = newToken.substring( 0, i ) + c + ( i + 1 < newToken.length() ? newToken.substring( i + 1 ) : "" );
                done = true;
            } else if ( c == 'z' ) {
                c = 'a';
                newToken = newToken.substring( 0, i ) + c + ( i + 1 < newToken.length() ? newToken.substring( i + 1 ) : "" );
            }
        }
        if ( done ) {
            return newToken;
        } else {
            // ok this is roll-over time
            boolean lastNumeric = false;
            boolean lastAlpha = false;
            boolean lastUpper = false;
            i = token.length();
            while ( !lastAlpha && !lastNumeric && i > 0 ) {
                i--;
                char c = token.charAt( i );
                lastAlpha = Character.isLetter( c );
                lastUpper = c == Character.toUpperCase( c );
                lastNumeric = Character.isDigit( c );
            }
            if ( lastAlpha ) {
                if ( lastUpper ) {
                    return token + 'A';
                }
                return token + 'a';
            }
            return token + '0';
        }
    }

    private static ArtifactVersion stripSnapshot( ArtifactVersion v ) {
        final String version = v.toString();
        final Matcher matcher = SNAPSHOT_PATTERN.matcher( version );
        if ( matcher.find() ) {
            return new DefaultArtifactVersion( version.substring( 0, matcher.start( 1 ) - 1 ) );
        }
        return v;
    }

    private static ArtifactVersion copySnapshot( ArtifactVersion source, ArtifactVersion destination ) {
        if ( ArtifactUtils.isSnapshot( destination.toString() ) ) {
            destination = stripSnapshot( destination );
        }
        final Pattern matchSnapshotRegex = SNAPSHOT_PATTERN;
        final Matcher matcher = matchSnapshotRegex.matcher( source.toString() );
        if ( matcher.find() ) {
            return new DefaultArtifactVersion( destination.toString() + "-" + matcher.group( 0 ) );
        } else {
            return new DefaultArtifactVersion( destination.toString() + "-SNAPSHOT" );
        }
    }
}
