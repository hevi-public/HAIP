package com.aiforum.acceptance

import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * JUnit Platform Suite that runs the Cucumber engine and explicitly selects the `features` classpath
 * resource. The @SelectClasspathResource is what makes feature discovery reliable under Gradle —
 * without it the cucumber engine only scans compiled-class roots, not the resources dir, and finds
 * nothing. Glue, plugin, and tag filter come from src/test/resources/junit-platform.properties.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
class RunCucumberTest
