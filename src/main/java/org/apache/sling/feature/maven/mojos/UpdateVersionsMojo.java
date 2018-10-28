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
import org.apache.sling.feature.io.json.SimpleFeatureJSONReader;
import org.apache.sling.feature.io.json.SimpleFeatureJSONWriter;
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


    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
    	if ( !versionsReportFile.exists() ) {
    		throw new MojoExecutionException("Dependency report file is missing. Please run '" +
    	      "mvn versions:dependency-updates-report -DdependencyUpdatesReportFormats=xml' first.");
    	}

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
    			getLog().info("Checking feature file " + entry.getKey());

    			final List<BundleUpdate> bundleUpdates = new ArrayList<>();
    			final List<ArtifactUpdate> artifactUpdates = new ArrayList<>();

                for(final Artifact bundle : entry.getValue().getBundles()) {
                	final String newVersion = update(bundle, root);
                	if ( newVersion != null ) {
                		final BundleUpdate update = new BundleUpdate();
                		update.bundle = bundle;
                		update.newVersion = newVersion;
                		bundleUpdates.add(update);
                	}
                }

                for(final Extension ext : entry.getValue().getExtensions()) {
                	if ( ext.getType() == ExtensionType.ARTIFACTS ) {
                		for(final Artifact a : ext.getArtifacts()) {
                        	final String newVersion = update(a, root);
                        	if ( newVersion != null ) {
                        		final ArtifactUpdate update = new ArtifactUpdate();
                        		update.extension = ext;
                        		update.artifact = a;
                        		update.newVersion = newVersion;
                        		artifactUpdates.add(update);
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
                		// search artifact
                		Artifact found = null;
                		for(final Artifact c : ext.getArtifacts()) {
                			if ( c.getId().equals(update.artifact.getId()) ) {
                				found = c;
                			}
                		}
                		if ( found == null ) {
                			throw new MojoExecutionException("Unable to update artifact in extension " + ext.getName() + " as variables are used: " + update.artifact.getId().toMvnId());
                		}
                		ext.getArtifacts().remove(found);
                		final Artifact newArtifact = new Artifact(new ArtifactId(update.artifact.getId().getGroupId(),
                				update.artifact.getId().getArtifactId(),
                				update.newVersion,
                				update.artifact.getId().getClassifier(),
                                update.artifact.getId().getType()));
                		newArtifact.getMetadata().putAll(update.artifact.getMetadata());
                		ext.getArtifacts().add(newArtifact);
                	}
                	try ( final Writer w = new FileWriter(out)) {
                		SimpleFeatureJSONWriter.write(w, rawFeature);
                	} catch (final IOException e) {
						throw new MojoExecutionException("Unable to write feature file " + entry.getValue(), e);
					}
                }
    		}
    	}
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

}
