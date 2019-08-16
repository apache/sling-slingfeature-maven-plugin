package org.apache.sling.feature.maven.mojos;

import org.junit.Before;
import org.junit.Test;
import org.mockito.internal.util.reflection.Whitebox;

import java.io.File;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

public class FeatureLauncherMojoTest {

    private FeatureLauncherMojo mojo = spy(new FeatureLauncherMojo());

    @Before
    public void setup() {
        doNothing().when(mojo).launch(any(String[].class));
    }

    @Test
    public void testLaunch() {
        Whitebox.setInternalState(mojo, "artifactClashOverrides", new String[] { "*:*:test" });
        Whitebox.setInternalState(mojo, "repositoryUrl", "~/.m2/repository");
        Whitebox.setInternalState(mojo, "frameworkProperties", new String[] { "one=two", "three=four" });
        Whitebox.setInternalState(mojo, "featureFile", new File("./test"));
        Whitebox.setInternalState(mojo, "variableValues", new String[] { "a=b" });
        Whitebox.setInternalState(mojo, "verbose", true);
        Whitebox.setInternalState(mojo, "cacheDirectory", new File("./launcher/cache"));
        Whitebox.setInternalState(mojo, "homeDirectory", new File("./launcher"));
        Whitebox.setInternalState(mojo, "extensionConfigurations", new String[] { "whatever" });
        Whitebox.setInternalState(mojo, "frameworkVersion", "1.0.0");
        Whitebox.setInternalState(mojo, "frameworkArtifacts", new String[] { "next-cool-thing" });
    }
}
