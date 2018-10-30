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

import javax.json.stream.JsonGenerator;
import javax.json.stream.JsonParsingException;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.utils.xml.Xpp3Dom;
import org.apache.maven.shared.utils.xml.Xpp3DomBuilder;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.feature.io.json.FeatureJSONWriter;
import org.apache.sling.feature.maven.ProjectHelper;

/**
 * Update the bundles/artifact versions
 */
@Mojo(
        name = "update-feature-versions",
        threadSafe = true
    )
public class UpdateVersionsMojo extends AbstractFeatureMojo {

    @Parameter(defaultValue = "target/dependency-updates-report.xml")
    private File versionsReportFile;

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

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
    	if ( !versionsReportFile.exists() ) {
    		throw new MojoExecutionException("Dependency report file is missing. Please run '" +
    	      "mvn versions:dependency-updates-report -DdependencyUpdatesReportFormats=xml' first.");
    	}

        final List<String[]> includes = parseMatches(updatesIncludesList, "include");
        final List<String[]> excludes = parseMatches(updatesExcludesList, "exclude");

    	// read report
    	final Xpp3Dom root;
    	try ( final Reader reader = new FileReader(versionsReportFile)) {
        	root = Xpp3DomBuilder.build(reader);
    	} catch ( final IOException ioe) {
    		throw new MojoExecutionException("Unable to read dependency report file at " + versionsReportFile + " : " + ioe.getMessage(), ioe);
    	}

    	if ( ProjectHelper.getFeatures(this.project).isEmpty()) {
    		throw new MojoExecutionException("No features found in project!");
    	}

    	for(final Map.Entry<String, Feature> entry : ProjectHelper.getFeatures(this.project).entrySet()) {
    		if ( !ProjectHelper.isAggregate(entry.getKey()) ) {
                if (dryRun) {
                    getLog().info("Checking feature file " + entry.getKey()
                            + " - dryRun is specified! Feature file is not changed!");
                } else {
                    getLog().info("Checking feature file " + entry.getKey());
                }
    			final List<BundleUpdate> bundleUpdates = new ArrayList<>();
    			final List<ArtifactUpdate> artifactUpdates = new ArrayList<>();

                for(final Artifact bundle : entry.getValue().getBundles()) {
                    if (shouldHandle(bundle.getId(), includes, excludes)) {
                        final String newVersion = update(bundle, root);
                        if (newVersion != null) {
                            final BundleUpdate update = new BundleUpdate();
                            update.bundle = bundle;
                            update.newVersion = newVersion;
                            bundleUpdates.add(update);
                        }
                    }
                }

                for(final Extension ext : entry.getValue().getExtensions()) {
                	if ( ext.getType() == ExtensionType.ARTIFACTS ) {
                		for(final Artifact a : ext.getArtifacts()) {
                            if (shouldHandle(a.getId(), includes, excludes)) {
                                final String newVersion = update(a, root);
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
                if (!bundleUpdates.isEmpty() || !artifactUpdates.isEmpty() ) {
                    final Feature rawFeature;
                	// we need to read the raw file
                	final File out = new File(entry.getKey());
                	try ( final Reader r = new FileReader(out)) {
                		rawFeature = SimpleFeatureJSONReader.read(r, entry.getKey());
                	} catch (final IOException e) {
						throw new MojoExecutionException("Unable to read feature file " + entry.getValue(), e);
                	}

                	// update bundles
                	for(final BundleUpdate update : bundleUpdates) {
                		if ( !rawFeature.getBundles().removeExact(update.bundle.getId()) ) {
                			throw new MojoExecutionException("Unable to update bundle as variables are used: " + update.bundle.getId().toMvnId());
                		}
                		final Artifact newBundle = new Artifact(new ArtifactId(update.bundle.getId().getGroupId(),
                				update.bundle.getId().getArtifactId(),
                				update.newVersion,
                				update.bundle.getId().getClassifier(),
                                update.bundle.getId().getType()));
                		newBundle.getMetadata().putAll(update.bundle.getMetadata());
                		rawFeature.getBundles().add(newBundle);
                	}

                	// update artifacts in extensions
                	for(final ArtifactUpdate update : artifactUpdates) {
                		final Extension ext = rawFeature.getExtensions().getByName(update.extension.getName());

                        if (!ext.getArtifacts().removeExact(update.artifact.getId())) {
                            throw new MojoExecutionException("Unable to update artifact in extension " + ext.getName()
                                    + " as variables are used: " + update.artifact.getId().toMvnId());
                        }
                        final Artifact newArtifact = new Artifact(new ArtifactId(update.artifact.getId().getGroupId(),
                                update.artifact.getId().getArtifactId(), update.newVersion,
                                update.artifact.getId().getClassifier(),
                                update.artifact.getId().getType()));
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

	private String update(final Artifact artifact, final Xpp3Dom root) throws MojoExecutionException {
		getLog().debug("Searching for updates of " + artifact.getId().toMvnId());

		String updated = null;

		// check dependencies
		final Xpp3Dom deps = root.getChild("dependencies");
		Xpp3Dom found = null;
		for(final Xpp3Dom dep : deps.getChildren("dependency")) {
			final String groupId = dep.getChild("groupId").getValue();
			final String artifactId = dep.getChild("artifactId").getValue();
			final String type = dep.getChild("type").getValue();
			final String classifier = dep.getChild("classifier").getValue();

			if ( artifact.getId().getGroupId().equals(groupId)
			   && artifact.getId().getArtifactId().equals(artifactId)
			   && artifact.getId().getType().equals(type)
			   && ((artifact.getId().getClassifier() == null && "null".equals(classifier))
				   || (artifact.getId().getClassifier() != null && artifact.getId().getClassifier().equals(classifier)) )) {
				found = dep;
				break;
			}
		}
		if ( found != null ) {
			getLog().debug("Found " + artifact.getId().toMvnId());
			// majors, minors, or incrementals
			final String version;
			if ( found.getChild("majors") != null ) {
				version = getLatest(found, "majors", "major");
			} else if ( found.getChild("minors") != null ) {
				version = getLatest(found, "minors", "minor");
			} else if ( found.getChild("incrementals") != null ) {
				version = getLatest(found, "incrementals", "incremental");
			} else {
				version = null;
			}
			if ( version != null ) {
				getLog().info("Updating " + artifact.getId().toMvnId() + " to " + version);

				updated = version;
			} else {
				getLog().debug("No newer version found for " + artifact.getId().toMvnId());
			}
		} else {
			throw new MojoExecutionException("Unable to find " + artifact.getId().toMvnId() + " in dependency update report");
		}
		return updated;
	}


	private String getLatest(final Xpp3Dom dep, final String groupName, final String itemName) {
		final Xpp3Dom group = dep.getChild(groupName);
		String result = null;
		for(final Xpp3Dom child : group.getChildren(itemName)) {
			result = child.getValue();
		}
		return result;
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
}
