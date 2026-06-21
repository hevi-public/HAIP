package com.aiforum.config

import com.aiforum.repo.PersonaRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Predefined personas declared in config (see `aiforum.seed.personas` in application.yml). On startup
 * any persona that doesn't already exist is inserted, so a fresh DB comes up with a usable team rather
 * than forcing the owner to hand-author personas before the forum is usable. Idempotent: existing
 * personas (matched by id) are left untouched, so this never clobbers owner edits or re-runs on reboot.
 *
 * Disabled under the `test` profile — acceptance scenarios author their own personas against a DB that
 * is wiped before each scenario, and seeded rows would just be noise.
 */
@Configuration
@Profile("!test")
@EnableConfigurationProperties(PersonaSeedProperties::class)
class PersonaSeeder(
    private val personas: PersonaRepository,
    private val props: PersonaSeedProperties,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val seeded = props.personas.count { persona ->
            (personas.find(persona.id) == null).also { missing ->
                if (missing) personas.insert(persona.id, persona.name, persona.descriptor, persona.model)
            }
        }
        if (seeded > 0) log.info("Seeded {} predefined persona(s) into the forum.", seeded)
    }
}

/**
 * `aiforum.seed.personas` — the predefined roster. Each entry carries the same fields the admin create
 * form collects; `slug` and `system_prompt` are derived by [PersonaRepository.insert]. A blank `model`
 * falls back to `aiforum.llm.default-model`.
 */
@ConfigurationProperties(prefix = "aiforum.seed")
data class PersonaSeedProperties(
    val personas: List<SeedPersona> = emptyList(),
) {
    data class SeedPersona(
        val id: String = "",
        val name: String = "",
        val descriptor: String = "",
        val model: String = "",
    )
}
