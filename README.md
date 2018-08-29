[<img src="http://sling.apache.org/res/logos/sling.png"/>](http://sling.apache.org)

 [![Build Status](https://builds.apache.org/buildStatus/icon?job=sling-slingfeature-maven-plugin-1.8)](https://builds.apache.org/view/S-Z/view/Sling/job/sling-slingfeature-maven-plugin-1.8) [![Test Status](https://img.shields.io/jenkins/t/https/builds.apache.org/view/S-Z/view/Sling/job/sling-slingfeature-maven-plugin-1.8.svg)](https://builds.apache.org/view/S-Z/view/Sling/job/sling-slingfeature-maven-plugin-1.8/test_results_analyzer/) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

# Apache Sling OSGi Feature Maven Plugin

This module is part of the [Apache Sling](https://sling.apache.org) project.

Maven Plugin for OSGi Applications

## Supported goals

### generate-resources
This goal processed feature files to substitute Maven variable placeholders in feature files such as `${project.groupId}`, `${project.artifactId}` and `${project.version}`

### attach-feature
Attach feature files to the projects produced artifacts

### assemble-feature
Produce an assembled feature from a list of features.

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

The generated feature will have the same `groupId`, `artifactId` and `version` as the pom in which 
the aggregation is configured. It will have type `slingfeature` and as classifier the one specified 
in the configuration.