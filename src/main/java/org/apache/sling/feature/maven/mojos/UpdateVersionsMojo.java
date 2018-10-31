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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.json.stream.JsonGenerator;
import javax.json.stream.JsonParsingException;

import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.path.PathTranslator;
import org.apache.maven.settings.Settings;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
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

/**
 * Update the bundles/artifact versions
 */
@Mojo(
        name = "update-feature-versions",
        threadSafe = true
    )
public class UpdateVersionsMojo extends AbstractFeatureMojo {

    /**
     * A comma separated list of artifact patterns to include. Follows the pattern
     * "groupId:artifactId:type:classifier:version". Designed to allow specifing the
     * set of includes from the command line.
     */
    @Parameter(property = "includes")
    private String updatesIncludesList;

    /**
     * A comma separated list of artifact patterns to exclude. Follows the pattern
     * "groupId:artifactId:type:classifier:version". Designed to allow specifing the
     * set of excludes from the command line.
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

    @Parameter(defaultValue = "${project.pluginArtifactRepositories}", readonly = true)
    protected List remotePluginRepositories;

    @Parameter(defaultValue = "${localRepository}", readonly = true)
    protected ArtifactRepository localRepository;

    @Component
    private WagonManager wagonManager;

    @Parameter(defaultValue = "${settings}", readonly = true)
    protected Settings settings;

    @Parameter(property = "maven.version.rules.serverId", defaultValue = "serverId")
    private String serverId;

    @Parameter(property = "maven.version.rules")
    private String rulesUri;

    @Component
    protected PathTranslator pathTranslator;

    @Component
    protected ArtifactResolver artifactResolver;

    private VersionsHelper getHelper() throws MojoExecutionException {
        return new DefaultVersionsHelper(artifactFactory, artifactResolver, artifactMetadataSource,
                remoteArtifactRepositories, remotePluginRepositories, localRepository, wagonManager, settings, serverId,
                rulesUri, getLog(), this.mavenSession, pathTranslator);
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

    private List<Map.Entry<String, Feature>> getFeatures() {
        final List<Map.Entry<String, Feature>> features = new ArrayList<>();
        for (final Map.Entry<String, Feature> entry : ProjectHelper.getFeatures(this.project).entrySet()) {
            if (!ProjectHelper.isAggregate(entry.getKey())) {
                features.add(entry);
            }
        }
        return features;
    }

    private Set<Dependency> getDependencies(final List<Map.Entry<String, Feature>> features) {
        final Set<Dependency> dependencies = new TreeSet<>(new DependencyComparator());
        for (final Map.Entry<String, Feature> entry : features) {
            for (final Artifact a : entry.getValue().getBundles()) {
                dependencies
                        .add(ProjectHelper.toDependency(a.getId(), org.apache.maven.artifact.Artifact.SCOPE_PROVIDED));
            }
            for (final Extension ext : entry.getValue().getExtensions()) {
                if (ext.getType() == ExtensionType.ARTIFACTS) {
                    for (final Artifact a : ext.getArtifacts()) {
                        dependencies.add(ProjectHelper.toDependency(a.getId(),
                                org.apache.maven.artifact.Artifact.SCOPE_PROVIDED));
                    }
                }
            }
        }
        return dependencies;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // get the features
        final List<Map.Entry<String, Feature>> features = this.getFeatures();
        if (features.isEmpty()) {
            throw new MojoExecutionException("No features found in project!");
        }

        // Calculate dependencies for features
        final Set<Dependency> dependencies = getDependencies(features);
        final List<Map.Entry<Dependency, String>> updates;
        try {
            updates = calculateUpdates(getHelper().lookupDependenciesUpdates(dependencies, false));
        } catch (ArtifactMetadataRetrievalException | InvalidVersionSpecificationException e) {
            throw new MojoExecutionException("Unable to calculate updates", e);
        }

        // get includes and excludes
        final List<String[]> includes = parseMatches(updatesIncludesList, "include");
        final List<String[]> excludes = parseMatches(updatesExcludesList, "exclude");

        for (final Map.Entry<String, Feature> entry : features) {
            if (dryRun) {
                getLog().info("Checking feature file " + entry.getKey()
                        + " - dryRun is specified! Feature file is not changed!");
            } else {
                getLog().info("Checking feature file " + entry.getKey());
            }
            final List<BundleUpdate> bundleUpdates = new ArrayList<>();
            final List<ArtifactUpdate> artifactUpdates = new ArrayList<>();

            for (final Artifact bundle : entry.getValue().getBundles()) {
                if (shouldHandle(bundle.getId(), includes, excludes)) {
                    final String newVersion = update(bundle, updates);
                    if (newVersion != null) {
                        final BundleUpdate update = new BundleUpdate();
                        update.bundle = bundle;
                        update.newVersion = newVersion;
                        bundleUpdates.add(update);
                    }
                }
            }

            for (final Extension ext : entry.getValue().getExtensions()) {
                if (ext.getType() == ExtensionType.ARTIFACTS) {
                    for (final Artifact a : ext.getArtifacts()) {
                        if (shouldHandle(a.getId(), includes, excludes)) {
                            final String newVersion = update(a, updates);
                            if (newVersion != null) {
                                final ArtifactUpdate update = new ArtifactUpdate();
                                update.extension = ext;
                                update.artifact = a;
                                update.newVersion = newVersion;
                                artifactUpdates.add(update);
                            }
                        }
                    }
                }
            }
            if (!bundleUpdates.isEmpty() || !artifactUpdates.isEmpty()) {
                final Feature rawFeature;
                // we need to read the raw file
                final File out = new File(entry.getKey());
                try (final Reader r = new FileReader(out)) {
                    rawFeature = SimpleFeatureJSONReader.read(r, entry.getKey());
                } catch (final IOException e) {
                    throw new MojoExecutionException("Unable to read feature file " + entry.getValue(), e);
                }

                // update bundles
                for (final BundleUpdate update : bundleUpdates) {
                    if (!rawFeature.getBundles().removeExact(update.bundle.getId())) {
                        throw new MojoExecutionException(
                                "Unable to update bundle as variables are used: " + update.bundle.getId().toMvnId());
                    }
                    final Artifact newBundle = new Artifact(new ArtifactId(update.bundle.getId().getGroupId(),
                            update.bundle.getId().getArtifactId(), update.newVersion,
                            update.bundle.getId().getClassifier(), update.bundle.getId().getType()));
                    newBundle.getMetadata().putAll(update.bundle.getMetadata());
                    rawFeature.getBundles().add(newBundle);
                }

                // update artifacts in extensions
                for (final ArtifactUpdate update : artifactUpdates) {
                    final Extension ext = rawFeature.getExtensions().getByName(update.extension.getName());

                    if (!ext.getArtifacts().removeExact(update.artifact.getId())) {
                        throw new MojoExecutionException("Unable to update artifact in extension " + ext.getName()
                                + " as variables are used: " + update.artifact.getId().toMvnId());
                    }
                    final Artifact newArtifact = new Artifact(new ArtifactId(update.artifact.getId().getGroupId(),
                            update.artifact.getId().getArtifactId(), update.newVersion,
                            update.artifact.getId().getClassifier(), update.artifact.getId().getType()));
                    newArtifact.getMetadata().putAll(update.artifact.getMetadata());
                    ext.getArtifacts().add(newArtifact);
                }
                if (!dryRun) {
                    try (final Writer w = new FileWriter(out)) {
                        SimpleFeatureJSONWriter.write(w, rawFeature);
                    } catch (final IOException e) {
                        throw new MojoExecutionException("Unable to write feature file " + entry.getValue(), e);
                    }
                }
    		}
    	}
    }

    private boolean shouldHandle(final ArtifactId id, final List<String[]> includes, final List<String[]> excludes) {
        boolean include = true;
        if (includes != null) {
            include = match(id, includes);
        }
        if (include && excludes != null) {
            include = !match(id, excludes);
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
                    && ((artifact.getId().getClassifier() == null && entry.getKey().getClassifier() == null)
                            || (artifact.getId().getClassifier() != null
                                    && artifact.getId().getClassifier().equals(entry.getKey().getClassifier())))) {
                found = entry.getValue();
                break;
            }

		}

		if ( found != null ) {
			getLog().debug("Found " + artifact.getId().toMvnId());
            getLog().info("Updating " + artifact.getId().toMvnId() + " to " + found);

            updated = found;
		} else {
            getLog().debug("No newer version found for " + artifact.getId().toMvnId());
        }

		return updated;
	}

	public static final class BundleUpdate {
		public Artifact bundle;
		public String newVersion;
	}

	public static final class ArtifactUpdate {
		public Extension extension;
		public Artifact artifact;
		public String newVersion;
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

    private List<Map.Entry<Dependency, String>> calculateUpdates(Map<Dependency, ArtifactVersions> updateInfos) {
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
}
