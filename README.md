[<img src="http://sling.apache.org/res/logos/sling.png"/>](http://sling.apache.org)

 [![Build Status](https://builds.apache.org/buildStatus/icon?job=sling-slingfeature-maven-plugin-1.8)](https://builds.apache.org/view/S-Z/view/Sling/job/sling-slingfeature-maven-plugin-1.8) [![Test Status](https://img.shields.io/jenkins/t/https/builds.apache.org/view/S-Z/view/Sling/job/sling-slingfeature-maven-plugin-1.8.svg)](https://builds.apache.org/view/S-Z/view/Sling/job/sling-slingfeature-maven-plugin-1.8/test_results_analyzer/) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

# Apache Sling OSGi Feature Maven Plugin

This module is part of the [Apache Sling](https://sling.apache.org) project.

Maven Plugin for OSGi Applications

## Supported goals

### generate-resources
This goal processed feature files to substitute Maven variable placeholders in feature files such as `${project.groupId}`, `${project.artifactId}` and `${project.version}`

### aggregate-features
Produce an aggregated feature from a list of features.

Sample configuration:

```
  <plugin>
    <groupId>org.apache.sling</groupId>
    <artifactId>slingfeature-maven-plugin</artifactId>
    <version>0.2.0-SNAPSHOT</version>
    <executions>
      <execution>
        <id>merge-features</id>
        <phase>generate-resources</phase>
        <goals>
          <goal>aggregate-features</goal>
        </goals>
        <configuration>
          <classifier>my-aggregated-feature</classifier>
          <features>
            <directory>
              <location>${basedir}/src/main/my-features</location>
              <includes>*.json</includes>
              <excludes>exclude-me.json</excludes>
              <excludes>exclude-me-too.json</excludes>
            </directory>
            <artifact>
              <groupId>org.apache.sling</groupId>
              <artifactId>org.apache.sling.myfeatures</artifactId>
              <version>1.2.3</version>
              <type>slingfeature</type>
              <classifier>someclassifier</classifier>
            </artifact>
          </features>
        </configuration>
      </execution>
    </executions>
  </plugin>  
```

All features found in both the directories as well as the artifact sections of the configuration are aggregated into a single feature. 

The merged feature will have the same `groupId`, `artifactId` and `version` as the pom in which 
the aggregation is configured. It will have type `slingfeature` and as classifier the one specified 
in the configuration.

#### Extension merging

Merging of extensions is specific to the extension being merged. Handlers can be provided to implement the logic of extension merging. A handler needs to implement the `org.apache.sling.feature.builder.FeatureExtensionHandler` and is looked up via the Java ServiceLoader mechanism.

To provide additional handlers to the `slingfeature-maven-plugin`, list the artifacts in the `<dependencies>` 
section of the plugin configuration:

```
  <plugin>
    <groupId>org.apache.sling</groupId>
    <artifactId>slingfeature-maven-plugin</artifactId>
    <version>0.2.0-SNAPSHOT</version>
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

### attach-features
Attach feature files found in the project to the projects produced artifacts. This includes features 
found in `src/main/features` as well as features produce with the `aggregate-features` goal.

