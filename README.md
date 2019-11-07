[<img src="https://sling.apache.org/res/logos/sling.png"/>](https://sling.apache.org)

 [![Build Status](https://builds.apache.org/buildStatus/icon?job=Sling/sling-slingfeature-maven-plugin/master)](https://builds.apache.org/job/Sling/job/sling-slingfeature-maven-plugin/job/master) [![Test Status](https://img.shields.io/jenkins/t/https/builds.apache.org/job/Sling/job/sling-slingfeature-maven-plugin/job/master.svg)](https://builds.apache.org/job/Sling/job/sling-slingfeature-maven-plugin/job/master/test_results_analyzer/) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.apache.sling/slingfeature-maven-plugin/badge.svg)](https://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.apache.sling%22%20a%3A%22slingfeature-maven-plugin%22) [![JavaDocs](https://www.javadoc.io/badge/org.apache.sling/slingfeature-maven-plugin.svg)](https://www.javadoc.io/doc/org.apache.sling/slingfeature-maven-plugin) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

# Apache Sling OSGi Feature Maven Plugin

This module is part of the [Apache Sling](https://sling.apache.org) project.

Maven Plugin for OSGi Applications

# Authoring Feature Files

The format of a feature file is described in the [Apache Sling Feature Model](https://github.com/apache/sling-org-apache-sling-feature/blob/master/readme.md).
As described there, a feature file must have an ID. If you use this Maven Plugin you have two options:

* Including the full ID in the source of your feature file
* Let the plugin generate the ID for you

## Including the ID in the feature file

If you want to have full control over the ID, you can encode it directly in the feature file. However, it is advised to use placeholders for the group id, artifact id
and version and let the maven plugin insert them for you.

In the following example the qualifier `base` is specified for the feature file:

```
   {
      "id":"${project.groupId}:${project.artifactId}:slingosgifeature:base:${project.version}",
      ...
   }
```

## Auto generated ID

If you omit the ID in your feature file, the maven plugin will generate an ID for you based on the file name of the feature file. The file name becomes the qualifier of the ID.
If your feature is named `feature.json` then this becomes the main artifact of the project without a qualifier.


# Global Configuration

* features : The directory containing the feature files. The default is `src/main/features`.
* featuresIncludes : The include pattern for feature files from the above directory. Default is `**/*.json`, therefore all files with the extension `.json` are read including sub directories.
* featuresExcludes : The exclude pattern for feature files from the above directory. Empty by default.
* includeArtifact : Include an feature specified as a Maven artifact.
* includeClassifier : Include a feature generated as aggreate with a classifier in this project.
* validateFeatures : Boolean switch defining whether the feature files should be validated against the schema. This is enabled by default.

This global configuration specifies the initial set of feature files used for the current project, the other goals can then refine this subset.

# Supported goals

Most of the plugin goals take a selection of features as input, for example to aggregate a set of features to a new feature or to analyse a specific set of features according to some rules.

All of these goals use the same way of selecting the features: Whenever a goal can select from the above global list of features, `filesInclude` and `filesExclude` can be used to select from the above list of project files. The patterns are relative to the specified features directory:

```
   ...
      <!-- Include all feature files with a filename starting with base -->
      <filesInclude>**/base-*.json</filesInclude>
      <!-- Include a specific file -->
      <filesInclude>additional/special-feature.json</filesInclude>
      <!-- Exclude this file -->
      <filesExclude>connectors/base-http.json</filesExclude>
   ...
```

The order of the above include statements defines the order in which the features are processed. If an include contains a pattern, all files matching that pattern are processed in string order based on their full path.

In addition, most of the goals can also be configured to select aggregated features (see below) based on their qualifier. The special token `*` can be used to select all aggregated features and the token `:` selects the aggregated feature without a classifier (main artifact).

```
   ...
      <includeClassifier>core-aggregate</includeClassifier>
      <includeClassifier>web-aggregate</includeClassifier>
   ...
```

Again the order of the instructions defines the order of processing.

Finally, most of the goals also support to add features from other projects:

```
   ...
      <includeArtifact>
          <groupId>org.apache.sling</groupId>
          <artifactId>somefeature</artifactId>
          <version>1.0.0</version>
          <type>slingosgifeature</type>
      </includeArtifact>
   ...
```

All of the above ways to select features (project files, project aggregates and external features) can be mixed in the configuration. It's possible to first specify an artifact include, followed by a aggregate classifier, followed by file includes. And this is then the order of processing. Please note that file excludes can be placed anywhere and regardless of their position, they are always applied to every files include.

## aggregate-features

Produce an aggregated feature from a list of features. The list of features is either specified by include/exclude patterns based on the configured features directory of the project or Maven coordinates of features.

Sample configuration:

```
  <plugin>
    <groupId>org.apache.sling</groupId>
    <artifactId>slingfeature-maven-plugin</artifactId>
    <version>0.2.1-SNAPSHOT</version>
    <extensions>true</extensions>
    <executions>
      <execution>
        <id>merge-features</id>
        <goals>
          <goal>aggregate-features</goal>
        </goals>
        <configuration>
          <aggregates>
               <!-- A list of feature aggregations, each aggregate creates a new feature: -->
              <aggregate>
                   <classifier/> <!-- optional classifier or main artifact (no classifier)-->
                   <title/>          <!-- optional title-->
                   <description/> <!-- optional description-->
                   <vendor/> <!-- optional description-->
                   <markAsFinal/> <!-- optional flag to mark the feature as final -->
                   <markAsComplete/> <!-- optional flag to mark the feature as complete -->
                   <filesInclude/> <!-- optional include for local files, this can be specified more than once -->
                   <filesExclude/>  <!-- optional exclude for local files, this can be specified more than once -->
                   <includeArtifact/>       <!-- optional artifact for external features, this can be specified more than once -->
                   <includeClassifier/>   <!-- optional classifier for aggregates, this can be specified more than once -->
                   <variablesOverrides>
                       <!-- Feature variables can be specified/overridden here -->
                       <https.port>8443</https.port>
                       <some.variable/> <!-- set some.variable to null -->
                   </variablesOverrides>
                   <frameworkPropertiesOverrides>
                       <!-- Framework property overrides go here -->
                       <org.osgi.framework.bootdelegation>sun.*,com.sun.*</org.osgi.framework.bootdelegation>
                   </frameworkPropertiesOverrides>
                   <artifactsOverrides>
                       <!-- Artifact clash overrides go here -->
                       <artifactsOverride>org.apache.sling:abundle:LATEST</artifactsOverride>
                       <artifactsOverride>org.apache.sling:anotherbundle:1.4.5</artifactsOverride>
                   </artifactsOverrides>
              </aggregate>
          </aggregates>
        </configuration>
      </execution>
    </executions>
  </plugin>
```

All features found in the directory as well as the artifact sections of the plugin configuration are aggregated into a single feature. Includes are processed in the way they appear in the configuration. If an include contains a pattern which includes more than one feature, than the features are included based on their full alphabetical file path. The features are aggregated in the order they are included.

If an include or an exclude is not using a pattern but directly specifying a file, this file must exists. Otherwise the build fails.

The merged feature will have the same `groupId`, `artifactId` and `version` as the pom in which the aggregation is configured. It will have type `slingosgifeature` and as classifier the one specified in the configuration named `classifier`.

Variables and framework properties can be overridden using the `<variables>` and
`<fraweworkProperties>` sections. If multiple definitions of the same variables are found
in the feature that are to be aggregated and the values for these variables are different,
they *must* be overridden, otherwise the aggregation will generate an error.

If the aggregation sources contain the same artifact more than once in different versions, 
a conflict resolution override 
must be specified using the `<artifactsOverride>` tag. In most cases the desired result will
only contain one version of the artifact, although it's also possible to state that all versions
must be kept. 

The following syntax is supported for `<artifactsOverride>`:

To provide a conflict resolution for a specific artifact:

    groupid1:artifactid1:<resolution>

To apply the same override rule for all clashes, a wildcard using '*' for
groupID and artifactID can be used:

    *:*:<resolution>

This means always select the same resolution in case of a clash.

Where `<resolution>` is one of the following:

* `ALL` - select all the artifacts, i.e. keep them side-by-side.
* `HIGHEST` - select only the artifact with the highest version number.
* `LATEST` - select only the artifact provided latest, so the version used in the last feature file merged in.
* `<version>` - selects this specific version.

When comparing version numbers these are converted to OSGi version
numbers and the OSGi version number ordering is applied.

### Extension merging

Merging of extensions is specific to the extension being merged. Handlers can be provided to implement the logic of extension merging. A handler needs to implement the `org.apache.sling.feature.builder.FeatureExtensionHandler` and is looked up via the Java ServiceLoader mechanism.

To provide additional handlers to the `slingfeature-maven-plugin`, list the artifacts in the `<dependencies>`
section of the plugin configuration:

```
  <plugin>
    <groupId>org.apache.sling</groupId>
    <artifactId>slingfeature-maven-plugin</artifactId>
    <version>0.2.1-SNAPSHOT</version>
    <executions>
      ...
    </executions>
    <dependencies>
      <dependency>
        <groupId>org.apache.sling</groupId>
        <artifactId>my-feature-extension-handler</artifactId>
        <version>1.0.0</version>
      </dependency>
    </dependencies>
  </plugin>
```

## analyse-features
Run feature model analysers on the feature models in the project. Analysers are defined in the
https://github.com/apache/sling-org-apache-sling-feature-analyser project and are selected by their ID,
which is obtained from the `getId()` method in
https://github.com/apache/sling-org-apache-sling-feature-analyser/blob/master/src/main/java/org/apache/sling/feature/analyser/task/AnalyserTask.java

```
<execution>
  <id>analyze</id>
  <goals>
    <goal>analyse-features</goal>
  </goals>
  <configuration>
    <scans>
    <!-- A list of scans, each creates a new analysis: -->
      <scan>
        <!-- specify which feature files to include -->
        <!-- optional include for local files, this can be specified more than once -->
        <filesInclude>**/*.json</filesInclude>

        <!-- optional exclude for local files, this can be specified more than once -->
        <filesExclude>dontcheck.json</filesExclude>

       <!-- optional classifier for aggregates, this can be specified more than once -->
       <includeClassifier>aggregated</includeClassifier

       <!-- optional artifact for external features, this can be specified more than once -->
       <includeArtifact>
           <groupId>org.apache.sling</groupId>
          <artifactId>somefeature</artifactId>
          <version>1.0.0</version>
          <type>slingosgifeature</type>
      </includeArtifact>

        <!-- if only a subset of tasks need to be run, specify them here -->
        <includeTask>api-regions-dependencies</includeTask>

        <!-- can also exclude tasks -->
        <excludeTask>do-not-run-this-task</excludeTask>

        <!-- taskConfiguration is a String, Properties map -->
        <taskConfiguration>
          <!-- the configuration is specific to the analyser tasks -->

          <!-- each key represents an AnalyserTask ID -->
          <api-regions-dependencies>
            <!-- can use keyname-textvalue syntax to specify configuration for a task -->
            <exporting-apis>global</exporting-apis>
            <hiding-apis>internal</hiding-apis>
          </api-regions-dependencies>
        </taskConfiguration>
      </scan>
    </scans>
  </configuration>
</execution>
```

## attach-features
Attach feature files found in the project to the projects produced artifacts. This includes features
found in `src/main/features` as well as features produce with the `aggregate-features` goal if no configuration is specified.

## extract-extension
This goal can be used to extract the contents of an extension into a local file, which may be useful for other tools that can work on the content of the extension.

The goal is configured as in the following example:

```
<execution>
    <id>extract-apiregions</id>
    <goals><goal>extract-extension</goal></goals>
    <configuration>
        <selection>
            <featuresInclude>feature-abc.json</featuresInclude>
            <extension>api-regions</extension>
            <outputDir>target/extracted</outputDir>
        </selection>        
    </configuration>
</execution>
```

This example extracts the `api-region` extension from the feature files `feature-abc.json` and puts it in the `target/extracted` directory. Feature files are selected as described in the Global Configuration above. 

Output files are written to the output directory follows where the file name is the classifier of the selected feature, followed by the extension name and a `.json` extension for JSON and `.txt` extension for others.

* JSON extensions: the file contains the raw JSON text.
* TEXT extensions: the file contains the text from the extension.
* ARTIFACT extensions: the file contains the Maven IDs for each artifact. One ID per line.


## update-feature-versions

The update feature versions goal can be used to check for updates of artifacts contained in the features
that are part of the project.

Without specifying any other arguments, all artifacts will be updated to the latest version found. You can specify
a dry run to just check if there are updates:
```
    mvn slingfeature:update-feature-versions -DdryRun=true
```

You can specify includes and excludes based on maven coordinates to further redefine which artifacts to update.
The most common use case is to specify the group id only. For example if you want to update only artifacts
with the Sling group id, use this command:
```
    mvn slingfeature:update-feature-versions -DdryRun=true -Dincludes=org.apache.sling
```
You can specify several includes and excludes by separating them by comma.

It's also possible to define the version scope, which means the policy how to update. By default *ANY* is used,
meaning the latest found version (excluding snapshots) is used. You can also specify *MAJOR*, *MINOR*, *INCREMENTAL*
and *SUBINCREMENTAL* by using the `versionScope` parameter.

Alternative you can combine this with the includes and specify a different scope per include:
```
    mvn slingfeature:update-feature-versions -DdryRun=true -Dincludes=org.apache.sling/MAJOR,org.apache.felix/INCREMENTAL
```

With an include you can also specify an exact version:
```
    mvn slingfeature:update-feature-versions -DdryRun=true -Dincludes=org.apache.jackrabbit.oak/4.0.2
```

## repository

With the repository goal, a directory with all artifacts from the selected features will be created.

```
<execution>
  <id>repo</id>
  <goals>
    <goal>repository</goal>
  </goals>
  <configuration>
      <repositories>
          <!-- A list of repositories, each creates a new repository: -->
         <repository>
              <repositoryDir/> <!-- optional repository directory-->
              <filesInclude/> <!-- optional include for local files, this can be specified more than once -->
              <filesExclude/>  <!-- optional exclude for local files, this can be specified more than once -->
              <includeArtifact/>       <!-- optional artifact for external features, this can be specified more than once -->
              <includeClassifier/>   <!-- optional classifier for aggregates, this can be specified more than once -->
              <embedArtifact/> <!-- optional artifact to be embedded in the repository. This can be specified more than once -->
         </repository>
     </repositories>
   </configuration>
 </execution>
```

## Feature Launcher (launch-features)

**Attention**: This Mojo is BETA meaning under development and new released
may change the way the Mojo is used.

This Mojo allows the user to launch a Feature(s) from a POM through a
profile. This can look like this:
```
<profile>
    <id>launch</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.sling</groupId>
                <artifactId>slingfeature-maven-plugin</artifactId>
                <version>1.0.7-SNAPSHOT</version>
                <extensions>true</extensions>
                <dependencies>
                    <!-- To Support the Deployment of Content Package the Extension Content
                         must be added BEFORE the Feature Launcher -->
                    <dependency>
                        <groupId>org.apache.sling</groupId>
                        <artifactId>org.apache.sling.feature.extension.content</artifactId>
                        <version>1.0.5-SNAPSHOT</version>
                    </dependency>
                    <dependency>
                        <groupId>org.apache.sling</groupId>
                        <artifactId>org.apache.sling.feature.launcher</artifactId>
                        <version>1.0.7-SNAPSHOT</version>
                    </dependency>
                </dependencies>
                <executions>
                    <execution>
                        <id>launch-it</id>
                        <phase>install</phase>
                        <goals>
                            <goal>launch-features</goal>
                        </goals>
                        <configuration>
                            <selection>
                                <includeClassifier>example-runtime</includeClassifier>
                            </selection>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>
```

Do avoid having to release this plugin every time a new Feature Launcher
is released the two are decoupled and the Feature Launcher is loaded at
runtime instead. For that the feature launcher must be added to the plugin
as dependency. If missing this Mojo with fail as it cannot find the launcher.

**Attention**: to deploy converted Content Packages the **Feature Content
Extension must added here as well and it must be place **AHEAD** of the
Feature Launcher.

Beside the Feature Files this Mojo for now supports all the parameters
of the current Feature Launcher (1.0.7-SNAPSHOT). For more info see the
FeautreLaucherMojoTest.testFullLaunch() test method.

## Features Diff (features-diff)

This MOJO compares different versions of the same Feature Model, producing the prototype
Feature Model that shows the differences.

```
<execution>
  <id>diff</id>
  <goals>
    <goal>features-diff</goal>
  </goals>
  <configuration>
    <selection>
      <includeClassifier>cloud-ready</includeClassifier>
      <includeClassifier>main-jar</includeClassifier>
    </selection>
    <comparisonVersion>1.0.0-beta</comparisonVersion> <!-- (,${project.version}) by default -->
  </configuration>
 </execution>
```

It will produce `${project.classifier}.json` JSON output files under the `${project.build.directory}/features-diff` directory,
where IDs will be composed by:

```
${project.groupId}:${project.artifactId}:${project.classifier}_updater:${project.version}
```

Diff JSON file will be attached to the project.
