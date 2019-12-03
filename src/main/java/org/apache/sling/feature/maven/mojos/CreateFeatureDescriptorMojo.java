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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Dependency;
import org.apache.maven.shared.transfer.artifact.install.ArtifactInstaller;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.transfer.artifact.install.ArtifactInstallerException;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.maven.mojos.manager.DefaultFeaturesManager;
import org.apache.sling.feature.maven.mojos.manager.FeaturesManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Create a Feature Model Descriptor (slingosigfeature) based on the
 * project's POM
 */
@Mojo(
    name = "create-fm-descriptor",
    requiresProject = true,
    threadSafe = true
)
public class CreateFeatureDescriptorMojo extends AbstractIncludingFeatureMojo {

    public static final String CFG_VERBOSE = "verbose";
    public static final String CFG_CLASSIFIER = "classifier";

    public static final String CFG_FM_OUTPUT_DIRECTORY = "featureModelsOutputDirectory";
    public static final String DEFAULT_FM_OUTPUT_DIRECTORY = "${project.build.directory}/fm";

    /**
     * Target directory for the Feature Model file
     */
    @Parameter(property = CFG_FM_OUTPUT_DIRECTORY, defaultValue = DEFAULT_FM_OUTPUT_DIRECTORY)
    private File fmOutput;

    /**
     * Optional Classifier for this Feature Model Descriptor file name
     */
    @Parameter(property = CFG_CLASSIFIER, required = false, defaultValue = "")
    private String classifier;

    /**
     * Framework Properties for the Launcher
     */
    @Parameter(property = CFG_VERBOSE, required = false, defaultValue = "false")
    private boolean verbose;

    @Component
    protected ArtifactInstaller installer;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkPreconditions();
        // Create a Feature Model Descriptor with its bundle in it
        FeaturesManager featuresManager = new DefaultFeaturesManager(
            true, 20, fmOutput, null, null, null
        );
        featuresManager.init(project.getGroupId(), project.getArtifactId(), project.getVersion());
        ArtifactId bundle = new ArtifactId(project.getGroupId(), project.getArtifactId(), project.getVersion(), "", "bundle");
        featuresManager.addArtifact("", bundle);
        List<Dependency> dependencies = project.getDependencies();
        for(Dependency dependency: dependencies) {
            if("compile".equals(dependency.getScope())) {
                featuresManager.addArtifact("", new ArtifactId(
                    dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), dependency.getClassifier(), dependency.getType()
                ));
            }
        }
        getLog().info("Project Features: " + featuresManager);
        try {
            featuresManager.serialize();
            // Now install the file as .slingosigfeature into the local Maven Repo
            installFMDescriptor(bundle);
        } catch(Exception e) {
            getLog().error("Failure to serialize Feature Manager", e);
        }
    }
    private void installFMDescriptor(ArtifactId artifact) {
        Collection<Artifact> artifacts = Collections.synchronizedCollection(new ArrayList<>());
        // Source FM Descriptor File Path
        String fmDescriptorFilePath = artifact.getArtifactId() + ".json";
        File fmDescriptorFile = new File(fmOutput, fmDescriptorFilePath);
        if(fmDescriptorFile.exists() && fmDescriptorFile.canRead()) {
            // Need to create a new Artifact Handler for the different extension and an Artifact to not
            // change the module artifact
            DefaultArtifactHandler fmArtifactHandler = new DefaultArtifactHandler("slingosgifeature");
            //AS TODO: For now the classifier is not set (check if we need to add the artifact id)
            DefaultArtifact fmArtifact = new DefaultArtifact(
                artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(),
                null, "slingosgifeature", classifier, fmArtifactHandler
            );
            fmArtifact.setFile(fmDescriptorFile);
            artifacts.add(fmArtifact);
            try {
                installArtifact(mavenSession.getProjectBuildingRequest(), artifacts);
            } catch (MojoFailureException | MojoExecutionException e) {
                getLog().error("Failed to install FM Descriptor", e);
            }
        } else {
            getLog().error("Could not find FM Descriptor File: " + fmDescriptorFile);
        }
    }

    private void installArtifact(ProjectBuildingRequest pbr, Collection<Artifact> artifacts )
        throws MojoFailureException, MojoExecutionException
    {
        try
        {
            installer.install(pbr, artifacts);
        }
        catch ( ArtifactInstallerException e )
        {
            throw new MojoExecutionException( "ArtifactInstallerException", e );
        }
    }
}
