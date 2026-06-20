package com.aiforum.acceptance

import io.cucumber.spring.CucumberContextConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * The ONE class that bootstraps Spring for Cucumber (see the cucumber-spring-bdd skill). Boots the
 * real app on a random port under the `test` profile, which activates the @Primary ScriptableLlmClient
 * and the fixed Clock. Step-definition classes (glue package com.aiforum.acceptance) get their
 * dependencies by constructor injection from this context.
 */
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CucumberSpringConfiguration
