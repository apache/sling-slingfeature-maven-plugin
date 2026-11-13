Apache Sling OSGi Feature Maven Plugin
======================================

Maven Plugin for building OSGi features and applications.

See [Goals](plugin-info.html) for a list of supported goals.

## Usage

As this Maven plugin comes with [Maven extensions](https://maven.apache.org/guides/mini/guide-using-extensions.html) (for defining custom bindings for `default` lifecycle and a custom artifact handler for type/packaging `slingosgifeature` as well as a `AbstractMavenLifecycleParticipant`) it needs to be loaded accordingly

```
<plugin>
  <groupId>${project.groupId}</groupId>
  <artifactId>${project.artifactId}</artifactId>
  <version>${project.version}</version>
  <extensions>true</extensions>
</plugin>
```

Additional Documentation can be found in the [README](https://github.com/apache/sling-slingfeature-maven-plugin/blob/master/README.md) page.

## API package exclusions

The `apis-jar` goal excludes an API region export if its properties contain
`api-jar-exclude`, regardless of the property's value. The package remains in the
API region definition, but is omitted from the generated API, source and Javadoc
JARs and is not reported as missing. This also applies when its toggle is enabled.

The existing per-bundle `apis-ignore` metadata remains supported.

## Plugin bindings for `slingosgifeature` packaging

```
<phases>
  <install>org.apache.sling:slingfeature-maven-plugin:attach-features,
           org.apache.maven.plugins:maven-install-plugin:install</install>
  <deploy>org.apache.maven.plugins:maven-deploy-plugin:deploy</deploy>
</phases>
```