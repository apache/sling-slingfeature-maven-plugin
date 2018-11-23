variablesOverrides[<img src="http://sling.apache.org/res/logos/sling.png"/>](http://sling.apache.org)

 [![Build Status](https://builds.apache.org/buildStatus/icon?job=sling-slingfeature-maven-plugin-1.8)](https://builds.apache.org/view/S-Z/view/Sling/job/sling-slingfeature-maven-plugin-1.8) [![Test Status](https://img.shields.io/jenkins/t/https/builds.apache.org/view/S-Z/view/Sling/job/sling-slingfeature-maven-plugin-1.8.svg)](https://builds.apache.org/view/S-Z/view/Sling/job/sling-slingfeature-maven-plugin-1.8/test_results_analyzer/) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

# Apache Sling OSGi Feature Maven Plugin

This module is part of the [Apache Sling](https://sling.apache.org) project.

Maven Plugin for OSGi Applications

# Global Configuration

* features : The directory containing the feature files. The default is `src/main/features`.
* featuresIncludes : The include pattern for feature files from the above directory. Default is `**/*.json`, therefore all files with the extension `.json` are read including sub directories.
* featuresExcludes : The exclude pattern for feature files from the above directory. Empty by default.
* validateFeatures : Boolean switch defining whether the feature files should be validated against the schema. This is enabled by default.

This global configuration specifies the initial set of feature files used for the current project, the other goals can then refine this subset.

## Supported goals

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

The order of the above include statements defines the order in which the features are processed. If an include contains a pattern, all files matching that pattern are processed in alphabetical order based on their filename.

In addition, most of the goals can also be configured to select aggregated features (see below) based on their qualifier. The special token `*` can be used to select all aggregated features and an empty classifier selects the aggregated feature without a classifier (main artifact).

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

### aggregate-features

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
                       <artifactsOverride>org.apache.sling:anotherbundle</artifactsOverride>
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


#### Extension merging

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

### analyse-features
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

### attach-features
Attach feature files found in the project to the projects produced artifacts. This includes features
found in `src/main/features` as well as features produce with the `aggregate-features` goal if no configuration is specified.

### repository

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
