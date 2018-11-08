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
import java.io.IOException;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.utils.io.DirectoryScanner;
import org.apache.maven.shared.utils.logging.MessageUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import com.github.fge.jsonschema.core.report.ProcessingMessage;
import com.github.fge.jsonschema.core.report.ProcessingReport;
import com.github.fge.jsonschema.main.JsonSchema;
import com.github.fge.jsonschema.main.JsonSchemaFactory;

@Mojo(
    name = "features-schema-validation",
    defaultPhase = LifecyclePhase.VALIDATE,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true
)
public final class ValidationFeaturesMojo extends AbstractMojo {

    @Parameter(defaultValue = "https://raw.githubusercontent.com/apache/sling-org-apache-sling-feature/master/schema/Feature-1.0.0.schema.json")
    private String jsonSchemaUri;

    @Parameter(defaultValue = "${basedir}/src/main/features")
    private File featuresDirectory;

    @Parameter(defaultValue = "**/*.json")
    private String[] includes;

    @Parameter(defaultValue = "")
    private String excludes;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        JsonSchemaFactory schemaFactory = JsonSchemaFactory.byDefault();

        JsonSchema schema = null;

        getLog().debug(MessageUtils.buffer().a("Retrieving the JSON Schema from ").strong(jsonSchemaUri).a("...").toString());

        try {
            schema = schemaFactory.getJsonSchema(jsonSchemaUri);
            getLog().debug(MessageUtils.buffer().a("JSON Schema ").strong(jsonSchemaUri).a(" successfully retrieved").toString());
        } catch (ProcessingException e) {
            throw new MojoExecutionException("An error occured when retrieving the JSON Schema from " + jsonSchemaUri, e);
        }

        getLog().debug(MessageUtils.buffer().a("Retrieving the JSON Feature files scanning ").strong(featuresDirectory).a(" directory...").toString());

        DirectoryScanner directoryScanner = new DirectoryScanner();
        directoryScanner.setBasedir(featuresDirectory);
        directoryScanner.setIncludes(includes);
        if (excludes != null && excludes.length() > 0) {
            directoryScanner.setExcludes(excludes);
        }
        directoryScanner.scan();

        ObjectMapper mapper = new ObjectMapper();

        boolean failed = false;

        for (String featureFileName : directoryScanner.getIncludedFiles()) {
            File featureFile = new File(featuresDirectory, featureFileName);
            JsonNode instance = null;

            getLog().debug(MessageUtils.buffer().a("Reading the Feature file ").strong(featureFile).a("...").toString());

            try {
                instance = mapper.readTree(featureFile);
                getLog().debug(MessageUtils.buffer().a("Feature file ").strong(featureFile).a(" successfully read").toString());
            } catch (IOException e) {
                throw new MojoExecutionException("An error occurred while reading " + featureFile + " Feature file:", e);
            }

            getLog().debug(MessageUtils.buffer().a("Validating the Feature file ").strong(featureFile).a("...").toString());

            try {
                ProcessingReport report = schema.validate(instance, true);

                if (report.isSuccess()) {
                    getLog().info(MessageUtils.buffer().a("Feature file ").mojo(featureFile).a(" validation passed").toString());
                } else {
                    failed = true;

                    getLog().error(MessageUtils.buffer().a("Feature file ").error(featureFile).a(" validation detected one or more errors:").toString());

                    for (ProcessingMessage message : report) {
                        String errorMessage = String.format(" * %s: %s",
                                message.asJson().get("schema").get("pointer").asText(),
                                message.getMessage() );

                        switch (message.getLogLevel()) {
                            case ERROR:
                            case FATAL:
                                getLog().error(errorMessage);
                                break;

                            default:
                                getLog().debug(errorMessage);
                                break;
                        }
                    }
                }
            } catch (ProcessingException e) {
                getLog().error(MessageUtils.buffer().a("An error occurred while validating Feature ").error(featureFile).a(", read the log for details:").toString(), e);
            }
        }

        if (failed) {
            throw new MojoFailureException("One or more features Feature file validation detected one or more error(s), please read the plugin log for more datils");
        } else {
            getLog().info("All processed Feature files are valid");
        }
    }

}
