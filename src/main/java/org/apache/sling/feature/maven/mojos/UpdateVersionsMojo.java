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
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.json.stream.JsonGenerator;
import javax.json.stream.JsonParsingException;

import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Artifacts;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.feature.io.json.FeatureJSONWriter;
import org.apache.sling.feature.maven.ProjectHelper;
import org.codehaus.mojo.versions.api.ArtifactVersions;
import org.codehaus.mojo.versions.api.DefaultVersionsHelper;
import org.codehaus.mojo.versions.api.UpdateScope;
import org.codehaus.mojo.versions.api.VersionsHelper;
import org.codehaus.mojo.versions.utils.DependencyComparator;
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
     * If set to true, no changes are performed
     */
    @Parameter(defaultValue = "false", property = "dryRun")
    private boolean dryRun;

    @Component
    protected org.apache.maven.artifact.factory.ArtifactFactory artifactFactory;

    @Component
    protected ArtifactMetadataSource artifactMetadataSource;

    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
    protected List remoteArtifactRepositories;

    @Parameter(defaultValue = "${localRepository}", readonly = true)
    protected ArtifactRepository localRepository;

    private VersionsHelper getHelper() throws MojoExecutionException {
        return new DefaultVersionsHelper(artifactFactory, artifactResolver, artifactMetadataSource,
                remoteArtifactRepositories, null, localRepository, null, null, null,
                null, getLog(), this.mavenSession, null);
    }

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

    private Map<String, Feature> getFeatures() throws MojoExecutionException {
        return this.selectAllFeatureFiles();
    }

    private void addDependencies(final Set<Dependency> dependencies, final List<Artifact> artifacts) {
        for (final Artifact a : artifacts) {
            dependencies.add(ProjectHelper.toDependency(a.getId(), org.apache.maven.artifact.Artifact.SCOPE_PROVIDED));
        }
    }

    private Set<Dependency> getDependencies(final Map<String, Feature> features) {
        final Set<Dependency> dependencies = new TreeSet<>(new DependencyComparator());
        for (final Map.Entry<String, Feature> entry : features.entrySet()) {
            addDependencies(dependencies, entry.getValue().getBundles());
            for (final Extension ext : entry.getValue().getExtensions()) {
                if (ext.getType() == ExtensionType.ARTIFACTS) {
                    addDependencies(dependencies, ext.getArtifacts());
                }
            }
        }
        return dependencies;
    }

    private Feature readRawFeature(final String fileName) throws MojoExecutionException {
        // we need to read the raw file
        final File out = new File(fileName);
        try (final Reader r = new FileReader(out)) {
            return SimpleFeatureJSONReader.read(r, fileName);
        } catch (final IOException e) {
            throw new MojoExecutionException("Unable to read feature file " + fileName, e);
        }
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // get the features
        final Map<String, Feature> assembledFeatures = this.getFeatures();
        if (assembledFeatures.isEmpty()) {
            throw new MojoExecutionException("No features found in project!");
        }

        final Map<String, Feature> features = new HashMap<>();
        for (final Map.Entry<String, Feature> entry : assembledFeatures.entrySet()) {
            features.put(entry.getKey(), ProjectHelper.getFeatures(project).get(entry.getKey()));
        }

        // create config
        final UpdateConfig cfg = new UpdateConfig();

        // Calculate dependencies for features
        final Set<Dependency> dependencies = getDependencies(features);
        // get updates
        try {
            cfg.updateInfos = calculateUpdateInfos(getHelper().lookupDependenciesUpdates(dependencies, false));
        } catch (ArtifactMetadataRetrievalException | InvalidVersionSpecificationException e) {
            throw new MojoExecutionException("Unable to calculate updates", e);
        }

        // get includes and excludes
        cfg.includes = parseMatches(updatesIncludesList, "include");
        cfg.excludes = parseMatches(updatesExcludesList, "exclude");

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
                        SimpleFeatureJSONWriter.write(w, rawFeature);
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

    private boolean shouldHandle(final ArtifactId id, final UpdateConfig cfg) {
        boolean include = true;
        if (cfg.includes != null) {
            include = match(id, cfg.includes);
        }
        if (include && cfg.excludes != null) {
            include = !match(id, cfg.excludes);
        }
        return include;
    }

    private boolean match(final ArtifactId id, final List<String[]> matches) {
        boolean match = false;

        for(final String[] m : matches) {
            match = id.getGroupId().equals(m[0]);
            if (match && m.length > 1) {
                match = id.getArtifactId().equals(m[1]);
            }
            if (match && m.length == 3) {
                match = id.getVersion().equals(m[2]);
            } else if (match && m.length == 4) {
                match = id.getVersion().equals(m[3]);
                if (match) {
                    match = id.getType().equals(m[2]);
                }
            } else if (match && m.length == 5) {
                match = id.getVersion().equals(m[4]);
                if (match) {
                    match = id.getType().equals(m[2]);
                    if (match) {
                        match = m[3].equals(id.getClassifier());
                    }
                }
            }
            if (match) {
                break;
            }
        }
        return match;
    }

    private String update(final Artifact artifact, final List<Map.Entry<Dependency, String>> updates)
            throws MojoExecutionException {
		getLog().debug("Searching for updates of " + artifact.getId().toMvnId());

		String updated = null;

        // check updates
        String found = null;
        for (final Map.Entry<Dependency, String> entry : updates) {
            if (artifact.getId().getGroupId().equals(entry.getKey().getGroupId())
                    && artifact.getId().getArtifactId().equals(entry.getKey().getArtifactId())
                    && artifact.getId().getType().equals(entry.getKey().getType())
                    && !artifact.getId().getVersion().equals(entry.getValue())
                    && ((artifact.getId().getClassifier() == null && entry.getKey().getClassifier() == null)
                            || (artifact.getId().getClassifier() != null
                                    && artifact.getId().getClassifier().equals(entry.getKey().getClassifier())))) {
                found = entry.getValue();
                break;
            }

		}

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

        List<Map.Entry<Dependency, String>> updateInfos;
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

	public static class SimpleFeatureJSONReader extends FeatureJSONReader {

		static final ArtifactId PLACEHOLDER_ID = new ArtifactId("_", "_", "1.0", null, null);

		/**
	     * Private constructor
	     * @param location Optional location
	     */
	    protected SimpleFeatureJSONReader(final String location) {
	        super(location);
	    }

	    @Override
		protected ArtifactId getFeatureId(Map<String, Object> map) throws IOException {
	    	final ArtifactId id;
	        if ( !map.containsKey("id") ) {
	        	id = PLACEHOLDER_ID;
	        } else {
	        	id = super.getFeatureId(map);
	        }
	        return id;
	    }

		/**
	     * Read a new feature from the reader
	     * The reader is not closed. It is up to the caller to close the reader.
	     *
	     * @param reader The reader for the feature
	     * @param location Optional location
	     * @return The read feature
	     * @throws IOException If an IO errors occurs or the JSON is invalid.
	     */
	    public static Feature read(final Reader reader, final String location)
	    throws IOException {
	        try {
	            final SimpleFeatureJSONReader mr = new SimpleFeatureJSONReader(location);
	            return mr.readFeature(reader);
	        } catch (final IllegalStateException | IllegalArgumentException | JsonParsingException e) {
	            throw new IOException(e);
	        }
	    }
	}

	public static class SimpleFeatureJSONWriter extends FeatureJSONWriter {

		private SimpleFeatureJSONWriter() {}

	    /**
	     * Writes the feature to the writer.
	     * The writer is not closed.
	     * @param writer Writer
	     * @param feature Feature
	     * @throws IOException If writing fails
	     */
	    public static void write(final Writer writer, final Feature feature)
	    throws IOException {
	        final SimpleFeatureJSONWriter w = new SimpleFeatureJSONWriter();
	        w.writeFeature(writer, feature);
	    }

	    @Override
		protected void writeFeatureId(JsonGenerator generator, Feature feature) {
	    	if ( !feature.getId().equals(SimpleFeatureJSONReader.PLACEHOLDER_ID) ) {
	            super.writeFeatureId(generator, feature);
	        }
	    }
	}

    private List<Map.Entry<Dependency, String>> calculateUpdateInfos(Map<Dependency, ArtifactVersions> updateInfos) {
        final List<Map.Entry<Dependency, String>> updates = new ArrayList<>();
        for (final Map.Entry<Dependency, ArtifactVersions> entry : updateInfos.entrySet()) {
            ArtifactVersion latest;
            if (entry.getValue().isCurrentVersionDefined()) {
                latest = entry.getValue().getNewestUpdate(UpdateScope.ANY, false);
            } else {
                ArtifactVersion newestVersion = entry.getValue().getNewestVersion(
                        entry.getValue().getArtifact().getVersionRange(),
                        false);
                latest = newestVersion == null ? null
                        : entry.getValue().getNewestUpdate(newestVersion, UpdateScope.ANY, false);
                if (latest != null
                        && ArtifactVersions.isVersionInRange(latest,
                                entry.getValue().getArtifact().getVersionRange())) {
                    latest = null;
                }
            }
            if (latest != null) {
                final String newVersion = latest.toString();
                updates.add(new Map.Entry<Dependency, String>() {

                    @Override
                    public String setValue(final String value) {
                        throw new IllegalStateException();
                    }

                    @Override
                    public String getValue() {
                        return newVersion;
                    }

                    @Override
                    public Dependency getKey() {
                        return entry.getKey();
                    }
                });
            }
        }

        return updates;
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
                if (pos == -1) {
                    throw new MojoExecutionException("MIST " + update.artifact.getId().toMvnId());
                }
                final Artifact newArtifact = new Artifact(new ArtifactId(update.artifact.getId().getGroupId(),
                        update.artifact.getId().getArtifactId(), update.newVersion,
                        update.artifact.getId().getClassifier(), update.artifact.getId().getType()));
                newArtifact.getMetadata().putAll(update.artifact.getMetadata());
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
            if (shouldHandle(a.getId(), cfg)) {
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
}
